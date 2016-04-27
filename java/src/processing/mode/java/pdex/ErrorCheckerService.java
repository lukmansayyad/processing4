/* -*- mode: java; c-basic-offset: 2; indent-tabs-mode: nil -*- */

/*
Part of the Processing project - http://processing.org
Copyright (c) 2012-15 The Processing Foundation

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License version 2
as published by the Free Software Foundation.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.

You should have received a copy of the GNU General Public License
along with this program; if not, write to the Free Software Foundation, Inc.
51 Franklin Street, Fifth Floor, Boston, MA 02110-1301, USA.
*/

package processing.mode.java.pdex;

import com.google.classpath.ClassPath;
import com.google.classpath.ClassPathFactory;
import com.google.classpath.RegExpResourceFilter;

import java.awt.EventQueue;
import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.BadLocationException;
import javax.swing.text.Document;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;

import processing.app.Library;
import processing.app.Messages;
import processing.app.Preferences;
import processing.app.Sketch;
import processing.app.SketchCode;
import processing.app.SketchException;
import processing.app.Util;
import processing.app.ui.EditorStatus;
import processing.app.ui.ErrorTable;
import processing.core.PApplet;
import processing.data.IntList;
import processing.data.StringList;
import processing.mode.java.JavaMode;
import processing.mode.java.JavaEditor;
import processing.mode.java.pdex.PreprocessedSketch.SketchInterval;
import processing.mode.java.pdex.TextTransform.OffsetMapper;
import processing.mode.java.preproc.PdePreprocessor;
import processing.mode.java.preproc.PdePreprocessor.Mode;


/**
 * The main error checking service
 */
@SuppressWarnings("unchecked")
public class ErrorCheckerService {

  protected final JavaEditor editor;

  /** The amazing eclipse ast parser */
  protected final ASTParser parser = ASTParser.newParser(AST.JLS8);

  /** Class path factory for ASTGenerator */
  protected final ClassPathFactory classPathFactory = new ClassPathFactory();

  /**
   * Used to indirectly stop the Error Checker Thread
   */
  private volatile boolean running;

  /**
   * ASTGenerator for operations on AST
   */
  protected final ASTGenerator astGenerator;

  /**
   * Regexp for import statements. (Used from Processing source)
   */
  // TODO: merge this with SourceUtils one
  public static final String IMPORT_REGEX =
    "(?:^|;)\\s*(import\\s+)((?:static\\s+)?\\S+)(\\s*;)";


  public ErrorCheckerService(JavaEditor editor) {
    this.editor = editor;
    astGenerator = new ASTGenerator(editor, this);
  }


  /**
   * Error checking doesn't happen before this interval has ellapsed since the
   * last request() call.
   */
  private final static long errorCheckInterval = 650;

  protected volatile PreprocessedSketch latestResult = PreprocessedSketch.empty();

  private Thread errorCheckerThread;
  private final BlockingQueue<Boolean> requestQueue = new ArrayBlockingQueue<>(1);
  private ScheduledExecutorService scheduler;
  private volatile ScheduledFuture<?> scheduledUiUpdate = null;
  private volatile long nextUiUpdate = 0;

  private final Runnable mainLoop = new Runnable() {
    @Override
    public void run() {
      running = true;

      latestResult = checkCode();

      if (!latestResult.hasSyntaxErrors && !latestResult.hasCompilationErrors) {
//      editor.showProblemListView(Language.text("editor.footer.console"));
        editor.showConsole();
      }
      // Make sure astGen has at least one CU to start with
      // This is when the loaded sketch already has syntax errors.
      // Completion wouldn't be complete, but it'd be still something
      // better than nothing
      if (ASTGenerator.SHOW_DEBUG_TREE) {
        astGenerator.updateDebugTree(latestResult.compilationUnit);
      }

      while (running) {
        try {
          requestQueue.take(); // blocking until there is more work
        } catch (InterruptedException e) {
          break;
        }
        requestQueue.clear();

        try {

          Messages.log("Starting error check");
          PreprocessedSketch result = checkCode();

          if (!JavaMode.errorCheckEnabled) {
            latestResult.problems.clear();
            Messages.log("Error Check disabled, so not updating UI.");
          }

          latestResult = result;

          if (ASTGenerator.SHOW_DEBUG_TREE) {
            astGenerator.updateDebugTree(latestResult.compilationUnit);
          }

          astGenerator.reloadShowUsage();

          if (JavaMode.errorCheckEnabled) {
            if (scheduledUiUpdate != null) {
              scheduledUiUpdate.cancel(true);
            }
            // Update UI after a delay. See #2677
            long delay = nextUiUpdate - System.currentTimeMillis();
            Runnable uiUpdater = new Runnable() {
              final PreprocessedSketch result = latestResult;

              @Override
              public void run() {
                if (nextUiUpdate > 0 &&
                    System.currentTimeMillis() >= nextUiUpdate) {
                  EventQueue.invokeLater(new Runnable() {
                    @Override
                    public void run() {
                      updateErrorTable(result.problems);
                      editor.updateErrorBar(result.problems);
                      editor.getTextArea().repaint();
                      editor.updateErrorToggle(result.hasSyntaxErrors || result.hasCompilationErrors);
                    }
                  });
                }
              }
            };
            scheduledUiUpdate = scheduler.schedule(uiUpdater, delay,
                                                   TimeUnit.MILLISECONDS);
          }
        } catch (Exception e) {
          e.printStackTrace();
        }
      }

      synchronized (astGenerator) {
        astGenerator.getGui().disposeAllWindows();
      }
      Messages.loge("Thread stopped: " + editor.getSketch().getName());

      latestResult = null;

      running = false;
    }
  };


  public void start() {
    scheduler = Executors.newSingleThreadScheduledExecutor();
    errorCheckerThread = new Thread(mainLoop);
    errorCheckerThread.start();
  }


  public void stop() {
    cancel();
    running = false;
    errorCheckerThread.interrupt();
    if (scheduler != null) {
      scheduler.shutdownNow();
    }
  }


  public void cancel() {
    requestQueue.clear();
    nextUiUpdate = 0;
    if (scheduledUiUpdate != null) {
      scheduledUiUpdate.cancel(true);
    }
  }


  public void request() {
    nextUiUpdate = System.currentTimeMillis() + errorCheckInterval;
    requestQueue.offer(Boolean.TRUE);
  }


  public void addListener(Document doc) {
    if (doc != null) doc.addDocumentListener(sketchChangedListener);
  }


  public ASTGenerator getASTGenerator() {
    return astGenerator;
  }


  protected final DocumentListener sketchChangedListener = new DocumentListener() {
    @Override
    public void insertUpdate(DocumentEvent e) {
      if (JavaMode.errorCheckEnabled) request();
    }

    @Override
    public void removeUpdate(DocumentEvent e) {
      if (JavaMode.errorCheckEnabled) request();
    }

    @Override
    public void changedUpdate(DocumentEvent e) {
      if (JavaMode.errorCheckEnabled) request();
    }
  };


  protected PreprocessedSketch checkCode() {

    PreprocessedSketch.Builder result = new PreprocessedSketch.Builder();
    PreprocessedSketch prevResult = latestResult;

    List<ImportStatement> coreAndDefaultImports = result.coreAndDefaultImports;
    List<ImportStatement> codeFolderImports = result.codeFolderImports;
    List<ImportStatement> programImports = result.programImports;

    Sketch sketch = result.sketch = editor.getSketch();
    String className = sketch.getName();

    StringBuilder workBuffer = new StringBuilder();

    // Combine code into one buffer
    IntList tabStartsList = new IntList();
    for (SketchCode sc : sketch.getCode()) {
      if (sc.isExtension("pde")) {
        tabStartsList.append(workBuffer.length());
        if (sketch.getCurrentCode().equals(sc)) {
          try {
            workBuffer.append(sc.getDocumentText());
          } catch (BadLocationException e) {
            e.printStackTrace();
          }
        } else {
          workBuffer.append(sc.getProgram());
        }
        workBuffer.append('\n');
      }
    }
    result.tabStartOffsets = tabStartsList.array();

    String pdeStage = result.pdeCode = workBuffer.toString();
    char[] compilableStageChars;

    { // Prepare core and default imports
      // TODO: do this only once
      PdePreprocessor p = editor.createPreprocessor(null);
      String[] defaultImports = p.getDefaultImports();
      String[] coreImports = p.getCoreImports();

      for (String imp : coreImports) {
        coreAndDefaultImports.add(ImportStatement.parse(imp));
      }
      for (String imp : defaultImports) {
        coreAndDefaultImports.add(ImportStatement.parse(imp));
      }
    }

    // Prepare code folder imports
    // TODO: do this only when code folder changes
    if (sketch.hasCodeFolder()) {
      File codeFolder = sketch.getCodeFolder();
      String codeFolderClassPath = Util.contentsToClassPath(codeFolder);
      StringList codeFolderPackages = Util.packageListFromClassPath(codeFolderClassPath);
      for (String item : codeFolderPackages) {
        codeFolderImports.add(ImportStatement.wholePackage(item));
      }
    }

    // TODO: convert unicode escapes to chars

    List<IProblem> problems = new ArrayList<>();

    SourceUtils.scrubCommentsAndStrings(workBuffer);

    Mode mode = PdePreprocessor.parseMode(workBuffer);

    // Prepare transforms to convert pde code into parsable code
    TextTransform toParsable = new TextTransform(pdeStage);
    toParsable.addAll(SourceUtils.insertImports(coreAndDefaultImports));
    toParsable.addAll(SourceUtils.insertImports(codeFolderImports));
    toParsable.addAll(SourceUtils.parseProgramImports(workBuffer, programImports));
    toParsable.addAll(SourceUtils.replaceTypeConstructors(workBuffer));
    toParsable.addAll(SourceUtils.replaceHexLiterals(workBuffer));
    toParsable.addAll(SourceUtils.wrapSketch(mode, className, workBuffer.length()));

    { // Refresh sketch classloader and classpath if imports changed
      boolean importsChanged = prevResult == null ||
          prevResult.classPath == null || prevResult.classLoader == null ||
          checkIfImportsChanged(programImports, prevResult.programImports) ||
          checkIfImportsChanged(codeFolderImports, prevResult.codeFolderImports);

      if (importsChanged) {
        String[] classPathArray = buildClassPath(programImports);
        URL[] urlArray = Arrays.stream(classPathArray)
            .map(path -> {
              try {
                return Paths.get(path).toUri().toURL();
              } catch (MalformedURLException e) {
                Messages.loge("malformed URL when preparing sketch classloader", e);
                return null;
              }
            })
            .filter(url -> url != null)
            .toArray(URL[]::new);
        result.classLoader = new URLClassLoader(urlArray, null);
        result.classPath = classPathFactory.createFromPaths(classPathArray);
        result.classPathArray = classPathArray;
      } else {
        result.classLoader = prevResult.classLoader;
        result.classPath = prevResult.classPath;
        result.classPathArray = prevResult.classPathArray;
      }
    }

    // Transform code to parsable state
    String parsableStage = toParsable.apply();
    OffsetMapper parsableMapper = toParsable.getMapper();

    // Create intermediate AST for advanced preprocessing
    CompilationUnit parsableCU =
        makeAST(parser, parsableStage.toCharArray(), COMPILER_OPTIONS);

    // Prepare advanced transforms which operate on AST
    TextTransform toCompilable = new TextTransform(parsableStage);
    toCompilable.addAll(SourceUtils.addPublicToTopLevelMethods(parsableCU));
    toCompilable.addAll(SourceUtils.replaceColorAndFixFloats(parsableCU));

    // Transform code to compilable state
    String compilableStage = toCompilable.apply();
    OffsetMapper compilableMapper = toCompilable.getMapper();
    compilableStageChars = compilableStage.toCharArray();

    // Create compilable AST to get syntax problems
    CompilationUnit compilableCU =
        makeAST(parser, compilableStageChars, COMPILER_OPTIONS);

    // Get syntax problems from compilable AST
    List<IProblem> syntaxProblems = Arrays.asList(compilableCU.getProblems());
    problems.addAll(syntaxProblems);
    result.hasSyntaxErrors = syntaxProblems.stream().anyMatch(IProblem::isError);

    // Generate bindings after getting problems - avoids
    // 'missing type' errors when there are syntax problems
    CompilationUnit bindingsCU =
        makeASTWithBindings(parser, compilableStageChars, COMPILER_OPTIONS,
                            className, result.classPathArray);

    // Show compilation problems only when there are no syntax problems
    if (!result.hasSyntaxErrors) {
      problems.clear(); // clear warnings, they will be generated again
      List<IProblem> bindingsProblems = Arrays.asList(bindingsCU.getProblems());
      problems.addAll(bindingsProblems);
      result.hasCompilationErrors = bindingsProblems.stream().anyMatch(IProblem::isError);
    }

    // Update builder
    result.offsetMapper = parsableMapper.thenMapping(compilableMapper);
    result.javaCode = compilableStage;
    result.compilationUnit = bindingsCU;

    // Build it
    PreprocessedSketch ps = result.build();

    { // Process problems
      List<Problem> mappedCompilationProblems = problems.stream()
          // Filter Warnings if they are not enabled
          .filter(iproblem -> !(iproblem.isWarning() && !JavaMode.warningsEnabled))
          // Hide a useless error which is produced when a line ends with
          // an identifier without a semicolon. "Missing a semicolon" is
          // also produced and is preferred over this one.
          // (Syntax error, insert ":: IdentifierOrNew" to complete Expression)
          // See: https://bugs.eclipse.org/bugs/show_bug.cgi?id=405780
          .filter(iproblem -> !iproblem.getMessage()
              .contains("Syntax error, insert \":: IdentifierOrNew\""))
          // Transform into our Problems
          .map(iproblem -> {
            int start = iproblem.getSourceStart();
            int stop = iproblem.getSourceEnd() + 1; // make it exclusive
            SketchInterval in = ps.mapJavaToSketch(start, stop);
            int line = ps.tabOffsetToTabLine(in.tabIndex, in.startTabOffset);
            Problem p = new Problem(iproblem, in.tabIndex, line);
            p.setPDEOffsets(in.startTabOffset, in.stopTabOffset);
            return p;
          })
          .collect(Collectors.toList());

      if (Preferences.getBoolean(JavaMode.SUGGEST_IMPORTS_PREF)) {
        Map<String, List<Problem>> undefinedTypeProblems = mappedCompilationProblems.stream()
            // Get only problems with undefined types/names
            .filter(p -> {
              int id = p.getIProblem().getID();
              return id == IProblem.UndefinedType || id == IProblem.UndefinedName;
            })
            // Group problems by the missing type/name
            .collect(Collectors.groupingBy(p -> p.getIProblem().getArguments()[0]));

        // TODO: cache this, invalidate if code folder or libraries change
        final ClassPath cp = undefinedTypeProblems.isEmpty() ?
            null :
            classPathFactory.createFromPaths(buildClassPath(null));

        // Get suggestions for each missing type, update the problems
        undefinedTypeProblems.entrySet().stream()
            .forEach(entry -> {
              String missingClass = entry.getKey();
              List<Problem> affectedProblems = entry.getValue();
              String[] suggestions = getImportSuggestions(cp, missingClass);
              affectedProblems.forEach(p -> p.setImportSuggestions(suggestions));
            });
      }

      ps.problems.addAll(mappedCompilationProblems);
    }

    return ps;
  }


  protected String[] buildClassPath(List<ImportStatement> neededImports) {
    JavaMode mode = (JavaMode) editor.getMode();
    Sketch sketch = editor.getSketch();

    StringBuilder classPath = new StringBuilder();

    // Code folder
    if (sketch.hasCodeFolder()) {
      File codeFolder = sketch.getCodeFolder();
      String codeFolderClassPath = Util.contentsToClassPath(codeFolder);
      classPath.append(codeFolderClassPath);
    }

    // Mode class path
    String coreClassPath = mode.getCoreLibrary().getClassPath();
    if (coreClassPath != null) {
      classPath.append(File.pathSeparator).append(coreClassPath);
    }

    // Core libraries
    for (Library lib : mode.coreLibraries) {
      classPath.append(File.pathSeparator).append(lib.getClassPath());
    }

    // Java runtime
    String rtPath = System.getProperty("java.home") +
        File.separator + "lib" + File.separator + "rt.jar";
    if (new File(rtPath).exists()) {
      classPath.append(File.pathSeparator).append(rtPath);
    } else {
      rtPath = System.getProperty("java.home") + File.separator + "jre" +
          File.separator + "lib" + File.separator + "rt.jar";
      if (new File(rtPath).exists()) {
        classPath.append(File.pathSeparator).append(rtPath);
      }
    }

    if (neededImports == null) {
      for (Library lib : mode.contribLibraries) {
        classPath.append(File.pathSeparator).append(lib.getClassPath());
      }
    } else {
      neededImports.stream()
          .map(ImportStatement::getPackageName)
          .filter(pckg -> !ignorableImport(pckg))
          .map(pckg -> {
            try {
              return mode.getLibrary(pckg); // TODO: this may not be thread-safe
            } catch (SketchException e) {
              return null;
            }
          })
          .filter(lib -> lib != null)
          .map(Library::getClassPath)
          .forEach(cp -> classPath.append(File.pathSeparator).append(cp));
    }

    // Make sure class path does not contain empty string (home dir)
    String[] paths = classPath.toString().split(File.pathSeparator);
    return Arrays.stream(paths)
        .filter(p -> p != null && !p.trim().isEmpty())
        .distinct()
        .toArray(String[]::new);
  }


  public static String[] getImportSuggestions(ClassPath cp, String className) {
    RegExpResourceFilter regf = new RegExpResourceFilter(
        Pattern.compile(".*"),
        Pattern.compile("(.*\\$)?" + className + "\\.class",
                        Pattern.CASE_INSENSITIVE));

    String[] resources = cp.findResources("", regf);
    return Arrays.stream(resources)
        // remove ".class" suffix
        .map(res -> res.substring(0, res.length() - 6))
        // replace path separators with dots
        .map(res -> res.replace('/', '.'))
        // replace inner class separators with dots
        .map(res -> res.replace('$', '.'))
        // sort, prioritize clases from java. package
        .sorted((o1, o2) -> {
          // put java.* first, should be prioritized more
          boolean o1StartsWithJava = o1.startsWith("java");
          boolean o2StartsWithJava = o2.startsWith("java");
          if (o1StartsWithJava != o2StartsWithJava) {
            if (o1StartsWithJava) return -1;
            return 1;
          }
          return o1.compareTo(o2);
        })
        .toArray(String[]::new);
  }


  protected static CompilationUnit makeAST(ASTParser parser,
                                           char[] source,
                                           Map<String, String> options) {
    parser.setSource(source);
    parser.setKind(ASTParser.K_COMPILATION_UNIT);
    parser.setCompilerOptions(options);
    parser.setStatementsRecovery(true);

    return (CompilationUnit) parser.createAST(null);
  }

  protected static CompilationUnit makeASTWithBindings(ASTParser parser,
                                                       char[] source,
                                                       Map<String, String> options,
                                                       String className,
                                                       String[] classPath) {
    parser.setSource(source);
    parser.setKind(ASTParser.K_COMPILATION_UNIT);
    parser.setCompilerOptions(options);
    parser.setStatementsRecovery(true);
    parser.setUnitName(className);
    parser.setEnvironment(classPath, null, null, false);
    parser.setResolveBindings(true);

    return (CompilationUnit) parser.createAST(null);
  }


  public CompilationUnit getLatestCU() {
    return latestResult.compilationUnit;
  }


  /**
   * Ignore processing packages, java.*.*. etc.
   */
  protected boolean ignorableImport(String packageName) {
    return (packageName.startsWith("java.") ||
            packageName.startsWith("javax."));
  }


  protected boolean ignorableSuggestionImport(String impName) {

    String impNameLc = impName.toLowerCase();

    List<ImportStatement> programImports = latestResult.programImports;
    List<ImportStatement> codeFolderImports = latestResult.codeFolderImports;

    boolean isImported = Stream
        .concat(programImports.stream(), codeFolderImports.stream())
        .anyMatch(impS -> {
          String packageNameLc = impS.getPackageName().toLowerCase();
          return impNameLc.startsWith(packageNameLc);
        });

    if (isImported) return false;

    final String include = "include";
    final String exclude = "exclude";

    if (impName.startsWith("processing")) {
      if (JavaMode.suggestionsMap.containsKey(include) && JavaMode.suggestionsMap.get(include).contains(impName)) {
        return false;
      } else if (JavaMode.suggestionsMap.containsKey(exclude) && JavaMode.suggestionsMap.get(exclude).contains(impName)) {
        return true;
      }
    } else if (impName.startsWith("java")) {
      if (JavaMode.suggestionsMap.containsKey(include) && JavaMode.suggestionsMap.get(include).contains(impName)) {
        return false;
      }
    }

    return true;
  }

  static private final Map<String, String> COMPILER_OPTIONS;
  static {
    Map<String, String> compilerOptions = new HashMap<>();

    JavaCore.setComplianceOptions(JavaCore.VERSION_1_7, compilerOptions);

    // See http://help.eclipse.org/mars/index.jsp?topic=%2Forg.eclipse.jdt.doc.isv%2Fguide%2Fjdt_api_options.htm&anchor=compiler

    final String[] generate = {
        JavaCore.COMPILER_LINE_NUMBER_ATTR,
        JavaCore.COMPILER_SOURCE_FILE_ATTR
    };

    final String[] ignore = {
        JavaCore.COMPILER_PB_UNUSED_IMPORT,
        JavaCore.COMPILER_PB_MISSING_SERIAL_VERSION,
        JavaCore.COMPILER_PB_RAW_TYPE_REFERENCE,
        JavaCore.COMPILER_PB_REDUNDANT_TYPE_ARGUMENTS,
        JavaCore.COMPILER_PB_UNCHECKED_TYPE_OPERATION
    };

    final String[] warn = {
        JavaCore.COMPILER_PB_NO_EFFECT_ASSIGNMENT,
        JavaCore.COMPILER_PB_NULL_REFERENCE,
        JavaCore.COMPILER_PB_POTENTIAL_NULL_REFERENCE,
        JavaCore.COMPILER_PB_REDUNDANT_NULL_CHECK,
        JavaCore.COMPILER_PB_POSSIBLE_ACCIDENTAL_BOOLEAN_ASSIGNMENT,
        JavaCore.COMPILER_PB_UNUSED_LABEL,
        JavaCore.COMPILER_PB_UNUSED_LOCAL,
        JavaCore.COMPILER_PB_UNUSED_OBJECT_ALLOCATION,
        JavaCore.COMPILER_PB_UNUSED_PARAMETER,
        JavaCore.COMPILER_PB_UNUSED_PRIVATE_MEMBER
    };

    for (String s : generate) compilerOptions.put(s, JavaCore.GENERATE);
    for (String s : ignore)   compilerOptions.put(s, JavaCore.IGNORE);
    for (String s : warn)     compilerOptions.put(s, JavaCore.WARNING);

    COMPILER_OPTIONS = Collections.unmodifiableMap(compilerOptions);
  }


  /**
   * Updates the error table in the Error Window.
   */
  protected void updateErrorTable(List<Problem> problems) {
    try {
      ErrorTable table = editor.getErrorTable();
      table.clearRows();

      Sketch sketch = editor.getSketch();
      for (Problem p : problems) {
        String message = p.getMessage();
        if (Preferences.getBoolean(JavaMode.SUGGEST_IMPORTS_PREF) &&
            p.getImportSuggestions() != null &&
            p.getImportSuggestions().length > 0) {
          message += " (double-click for suggestions)";
        }

        table.addRow(p, message,
                     sketch.getCode(p.getTabIndex()).getPrettyName(),
                     Integer.toString(p.getLineNumber() + 1));
        // Added +1 because lineNumbers internally are 0-indexed
      }
    } catch (Exception e) {
      Messages.loge("Exception at updateErrorTable()", e);
      e.printStackTrace();
      cancel();
    }
  }


  /**
   * Updates editor status bar, depending on whether the caret is on an error
   * line or not
   */
  public void updateEditorStatus() {
//    if (editor.getStatusMode() == EditorStatus.EDIT) return;

    // editor.statusNotice("Position: " +
    // editor.getTextArea().getCaretLine());
    if (JavaMode.errorCheckEnabled) {
      LineMarker errorMarker = editor.findError(editor.getTextArea().getCaretLine());
      if (errorMarker != null) {
        if (errorMarker.getType() == LineMarker.WARNING) {
          editor.statusMessage(errorMarker.getProblem().getMessage(),
                               EditorStatus.CURSOR_LINE_WARNING);
        } else {
          editor.statusMessage(errorMarker.getProblem().getMessage(),
                               EditorStatus.CURSOR_LINE_ERROR);
        }
      } else {
        switch (editor.getStatusMode()) {
          case EditorStatus.CURSOR_LINE_ERROR:
          case EditorStatus.CURSOR_LINE_WARNING:
            editor.statusEmpty();
            break;
        }
      }
    }

//    // This line isn't an error line anymore, so probably just clear it
//    if (editor.statusMessageType == JavaEditor.STATUS_COMPILER_ERR) {
//      editor.statusEmpty();
//      return;
//    }
  }


  // TODO: does this belong here?
  // Thread: EDT
  public void scrollToErrorLine(Problem p) {
    if (p == null) return;
    highlightTabRange(p.getTabIndex(), p.getStartOffset(), p.getStopOffset());
  }

  // TODO: does this belong here?
  // Thread: EDT
  public void highlightTabRange(int tabIndex, int startTabOffset, int stopTabOffset) {
    if (editor == null) return;

    // Switch to tab
    editor.toFront();
    editor.getSketch().setCurrentCode(tabIndex);

    // Make sure offsets are in bounds
    int length = editor.getTextArea().getDocumentLength();
    startTabOffset = PApplet.constrain(startTabOffset, 0, length);
    stopTabOffset = PApplet.constrain(stopTabOffset, 0, length);

    // Highlight the code
    editor.getTextArea().select(startTabOffset, stopTabOffset);

    // Scroll to error line
    editor.getTextArea().scrollToCaret();
    editor.repaint();
  }


  public void highlightNode(ASTNode node) {
    PreprocessedSketch ps = latestResult;
    SketchInterval si = ps.mapJavaToSketch(node);
    EventQueue.invokeLater(() -> {
      highlightTabRange(si.tabIndex, si.startTabOffset, si.stopTabOffset);
    });
  }


  /**
   * Checks if import statements in the sketch have changed. If they have,
   * compiler classpath needs to be updated.
   */
  protected static boolean checkIfImportsChanged(List<ImportStatement> prevImports,
                                                 List<ImportStatement> imports) {
    if (imports.size() != prevImports.size()) {
      return true;
    } else {
      int count = imports.size();
      for (int i = 0; i < count; i++) {
        if (!imports.get(i).isSameAs(prevImports.get(i))) {
          return true;
        }
      }
    }
    return false;
  }


  public void handleErrorCheckingToggle() {
    if (!JavaMode.errorCheckEnabled) {
      Messages.log(editor.getSketch().getName() + " Error Checker disabled.");
      editor.getErrorPoints().clear();
      latestResult.problems.clear();
      updateErrorTable(Collections.<Problem>emptyList());
      updateEditorStatus();
      editor.getTextArea().repaint();
      editor.repaintErrorBar();
    } else {
      Messages.log(editor.getSketch().getName() + " Error Checker enabled.");
      request();
    }
  }

}
