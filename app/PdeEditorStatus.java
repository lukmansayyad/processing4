import java.awt.*;
import java.awt.event.*;


public class PdeEditorStatus extends Panel 
  implements ActionListener /*, Runnable*/ {
  static Color bgColor[];
  static Color fgColor[];


  static final int NOTICE = 0;
  static final int ERROR  = 1;
  static final int PROMPT = 2;

  static final int YES    = 1;
  static final int NO     = 2;
  static final int CANCEL = 3;

  static final String PROMPT_YES     = "yes";
  static final String PROMPT_NO      = "no";
  static final String PROMPT_CANCEL  = "cancel";
  static final String NO_MESSAGE     = "";

  static final int BUTTON_WIDTH  = 66;
  static final int BUTTON_HEIGHT = 20;

  PdeEditor editor;

  int mode;
  String message;

  Font font;
  FontMetrics metrics;
  int ascent;

  Image offscreen;
  int sizeW, sizeH;
  int imageW, imageH;

  Button yesButton;
  Button noButton;
  Button cancelButton;
  //Thread promptThread;
  int response;


  public PdeEditorStatus(PdeEditor editor) {
    this.editor = editor;
    empty();

    if (bgColor == null) {
      bgColor = new Color[3];
      bgColor[0] = PdeBase.getColor("editor.status.notice.bgcolor",
				      new Color(102, 102, 102));
      bgColor[1] = PdeBase.getColor("editor.status.error.bgcolor",
				      new Color(102, 26, 0));
      bgColor[2] = PdeBase.getColor("editor.status.prompt.bgcolor",
				      new Color(204, 153, 0));
      fgColor = new Color[3];
      fgColor[0] = PdeBase.getColor("editor.status.notice.fgcolor",
				      new Color(255, 255, 255));
      fgColor[1] = PdeBase.getColor("editor.status.error.fgcolor",
				      new Color(255, 255, 255));
      fgColor[2] = PdeBase.getColor("editor.status.prompt.fgcolor",
				      new Color(0, 0, 0));
    }
  }


  public void empty() {
    mode = NOTICE;
    message = NO_MESSAGE;
    update();
  }


  public void notice(String message) {
    mode = NOTICE;
    this.message = message;
    update();
  }

  public void unnotice(String unmessage) {
    if (message.equals(unmessage)) empty();
  }


  public void error(String message) {
    mode = ERROR;
    this.message = message;
    update();
  }


  /*
  public void run() {
    while (Thread.currentThread() == promptThread) {
      if (response != 0) {
	//System.out.println("stopping prompt thread");
	//promptThread.stop();
	//System.out.println("exiting prompt loop");
	unprompt();
	break;

      } else {
	try {
	  //System.out.println("inside prompt thread " + 
	  //System.currentTimeMillis());
	  Thread.sleep(100);
	} catch (InterruptedException e) { }
      }
    }
  }
  */

  public void prompt(String message) {
    mode = PROMPT;
    this.message = message;

    response = 0;
    yesButton.setVisible(true);
    noButton.setVisible(true);
    cancelButton.setVisible(true);
    yesButton.requestFocus();

    update();

    //promptThread = new Thread(this);
    //promptThread.start();
  }

  // prompt has been handled, re-hide the buttons
  public void unprompt() {
    yesButton.setVisible(false);
    noButton.setVisible(false);
    cancelButton.setVisible(false);
    //promptThread = null;
    empty();
  }


  public void update() {
    Graphics g = this.getGraphics();
    if (g != null) paint(g);
  }

  public void update(Graphics g) {
    paint(g);
  }

  public void paint(Graphics screen) {
    //if (screen == null) return;
    if (yesButton == null) {
      yesButton = new Button(PROMPT_YES);
      noButton = new Button(PROMPT_NO);
      cancelButton = new Button(PROMPT_CANCEL);
      setLayout(null);

      yesButton.addActionListener(this);
      noButton.addActionListener(this);
      cancelButton.addActionListener(this);

      add(yesButton);
      add(noButton);
      add(cancelButton);

      yesButton.setVisible(false);
      noButton.setVisible(false);
      cancelButton.setVisible(false);
    }

    Dimension size = getSize();
    if ((size.width != sizeW) || (size.height != sizeH)) {
      // component has been resized

      if ((size.width > imageW) || (size.height > imageH)) {
	// nix the image and recreate, it's too small
	offscreen = null;

      } else {
	// who cares, just resize
	sizeW = size.width; 
	sizeH = size.height;
	setButtonBounds();
      }
    }

    if (offscreen == null) {
      sizeW = size.width;
      sizeH = size.height;
      setButtonBounds();
      imageW = sizeW;
      imageH = sizeH;
      offscreen = createImage(imageW, imageH);
    }

    Graphics g = offscreen.getGraphics();
    if (font == null) {
      font = PdeBase.getFont("editor.status.font",
			       new Font("SansSerif", Font.PLAIN, 10));
      //font = new Font("SansSerif", Font.PLAIN, 10);
      g.setFont(font);
      metrics = g.getFontMetrics();
      ascent = metrics.getAscent();
    }

    g.setColor(bgColor[mode]);
    g.fillRect(0, 0, imageW, imageH);

    g.setColor(fgColor[mode]);
    g.drawString(message, PdeEditor.INSET_SIZE, (sizeH + ascent) / 2);

    screen.drawImage(offscreen, 0, 0, null);
  }


  protected void setButtonBounds() {
    int top = (sizeH - BUTTON_HEIGHT) / 2;

    int cancelLeft = sizeW - PdeEditor.INSET_SIZE - BUTTON_WIDTH;
    int noLeft = cancelLeft - PdeEditor.INSET_SIZE - BUTTON_WIDTH;
    int yesLeft = noLeft - PdeEditor.INSET_SIZE - BUTTON_WIDTH;

    yesButton.setBounds(yesLeft, top, BUTTON_WIDTH, BUTTON_HEIGHT);
    noButton.setBounds(noLeft, top, BUTTON_WIDTH, BUTTON_HEIGHT);
    cancelButton.setBounds(cancelLeft, top, BUTTON_WIDTH, BUTTON_HEIGHT);
  }


  public Dimension getPreferredSize() {
    return new Dimension(300, PdeEditor.GRID_SIZE);
  }


  public void actionPerformed(ActionEvent e) {
    if (e.getSource() == noButton) {
      //System.out.println("clicked no");
      //response = 2;
      // shut everything down, clear status, and return
      unprompt();
      editor.checkModified2();

    } else if (e.getSource() == yesButton) {
      //System.out.println("clicked yes");
      //response = 1;
      // shutdown/clear status, and call checkModified2
      unprompt();
      editor.doSave();  // assuming that something is set? hmm
      //System.out.println("calling checkmodified2");
      editor.checkModified2();

    } else if (e.getSource() == cancelButton) { 
      unprompt();
    }
  }
}


  /*
  Color noticeBgColor = new Color(102, 102, 102);
  Color noticeFgColor = new Color(255, 255, 255);

  Color errorBgColor = new Color(102, 26, 0);
  Color errorFgColor = new Color(255, 255, 255);

  Color promptBgColor = new Color(204, 153, 0);
  Color promptFgColor = new COlor(0, 0, 0);
  */
