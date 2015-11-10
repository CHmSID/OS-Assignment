import javax.sound.sampled.*;
import java.applet.Applet;
import java.awt.*;
import java.awt.event.*;
import java.io.*;

class BoundedBuffer {
	//an array of fixed size.
	private byte [][] buffer;
	private int nextIn, nextOut, ins, outs = 0;
	private boolean dataAvailable = false;          //false = buffer is empty.
	private boolean roomAvailable = true;           //true  = buffer is empty.

	public BoundedBuffer(Player player) {
		buffer = new byte [10][player.sizeOneSecond()];
	}

	// Inserts a chunk into bounded buffer
	public synchronized void insertChunk(byte [] chunk){
		while (!roomAvailable)
			try{
				wait();
			} catch(InterruptedException e){
				e.printStackTrace();
			}

		buffer[nextIn] = chunk;
		nextIn = (nextIn +1) % 10;
		ins++;

		if (nextIn == nextOut)
			roomAvailable = false;

		dataAvailable = true;

		notifyAll();
	}

	// Removes a chunk from the bounded buffer
	public synchronized byte [] removeChunk() {    
		while (!dataAvailable)
			try{
				wait();
			} catch(InterruptedException e){
				e.printStackTrace();
			}

		byte[] chunk = buffer[nextOut];
		nextOut = (nextOut +1) % 10;
		outs++;

		if (ins == outs)
			dataAvailable = false;

		roomAvailable = true;

		notifyAll();
		return chunk;
	}

	// Returns true if number of written data == number of read data
	public synchronized boolean ioEven() {
		return ins == outs;
	}

	// Resets bounded buffer
	public synchronized void clean() {
		nextIn = nextOut = ins = outs = 0;
		dataAvailable = false;
		roomAvailable = true;
		notifyAll();
	}
}

class Producer implements Runnable {
	private Player player;

	public Producer(Player player) {
		this.player = player;
	}

	public void run() {
		int bytesRead = 0;

		// Keep reading until no more audio or player stopped
		while (bytesRead != -1 && player.isPlaying()) {
			// Create a new chunk of correct size
			byte[] chunk = new byte[player.sizeOneSecond()];

			// Read a chunk
			try{
				bytesRead = player.getStream().read(chunk);
			}
			catch(IOException e){
				e.printStackTrace();
			}

			// Insert chunk into the buffer
			player.getBuffer().insertChunk(chunk);
		}

		// Producer finished reading audio file
		player.setFinished(true);

		player.print("Producer says: goodbye\n");
	}
}

class Consumer implements Runnable {
	private BoundedBuffer buffer;
	private SourceDataLine line;

	private Player player;

	public Consumer(Player player){
		this.player = player;
		buffer = player.getBuffer();
		line = player.getLine();
	}

	public void run() {
		// Only try to read when the player is playing and audio isn't finished
		while (player.isPlaying() && !player.isFinished()) {
			byte[] data = buffer.removeChunk();
			// Pass data to audio device
			line.write(data, 0, data.length);
		}

		player.print("Consumer says: byebye\n");
	}

}

class Player extends Panel implements Runnable {
	private static final long serialVersionUID = 1L;
	private TextField textfield;
	private TextArea textarea;
	private Font font;
	private String filename;

	private AudioInputStream s;
	private AudioFormat format;
	private SourceDataLine line;
	private BoundedBuffer buffer;
	private int oneSecond;
	private boolean isPlaying;
	private boolean finished;

	public Player(String filename) {
		font = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
		textfield = new TextField();
		textarea = new TextArea();
		textarea.setFont(font);
		textfield.setFont(font);
		setLayout(new BorderLayout());
		add(BorderLayout.SOUTH, textfield);
		add(BorderLayout.CENTER, textarea);

		buffer = new BoundedBuffer(this);

		textfield.addActionListener(new ActionListener() {
			public void actionPerformed(ActionEvent e) {
				String cmd = e.getActionCommand().toLowerCase();

				if (cmd.equals("x")) {
					print("Command received: halt playback\n");
					buffer.clean();
					setPlaying(false);
				}
				else print("Unrecognised command\n");

				textfield.setText("\0");
			}
		});

		this.filename = filename;

		new Thread(this).start();
	}

	public void run() {
		// Initiliazation
		try {
			s = AudioSystem.getAudioInputStream(new File(filename));
			format = s.getFormat();     

			print("Audio file: " + filename + "\n");
			print("Audio format: " + format.toString() + "\n");

			oneSecond = (int) (format.getChannels() * format.getSampleRate() * 
			format.getSampleSizeInBits() / 8);

			print("Audio file duration: " + 
				(s.getFrameLength() * format.getFrameSize() / oneSecond) + 
				" seconds\n");

			DataLine.Info info = 
				new DataLine.Info(SourceDataLine.class, format);

			if (!AudioSystem.isLineSupported(info)) {
				throw new UnsupportedAudioFileException();
			}

			// Set up audio line
			line = (SourceDataLine) AudioSystem.getLine(info);
			line.open(format);
			line.start();
		}
		catch (LineUnavailableException e) {
			e.printStackTrace();
		}
		catch (UnsupportedAudioFileException e) {
			e.printStackTrace();
		}
		catch(IOException e) {
			e.printStackTrace();
		}

		isPlaying = true;

		Thread producer = new Thread(new Producer(this));
		Thread consumer = new Thread(new Consumer(this));

		producer.start();
		consumer.start();

		// Allow threads to finish their work
		try {
			producer.join();
			consumer.join();
		}
		catch (InterruptedException e) {
			e.printStackTrace();
		}

		// Close audio line
		line.drain();
		line.stop();
		line.close();

		print("Main thread: bye bye\n");
	}

	public BoundedBuffer getBuffer() {
		return buffer;
	}

	public int sizeOneSecond() {
		return oneSecond;
	}

	public SourceDataLine getLine() {
		return line;
	}

	public AudioInputStream getStream() {
		return s;
	}

	public void setPlaying(boolean status) {
		isPlaying = status;
	}

	public boolean isPlaying() {
		return isPlaying;
	}

	public boolean isFinished() {
		return finished && buffer.ioEven();
	}

	public void setFinished(boolean status) {
		finished = status;
	}

	// Allows printing to the window from different threads
	public synchronized void print(String text) {
		textarea.append(text);
	}
}

public class StudentPlayerApplet extends Applet {
	private static final long serialVersionUID = 1L;
	public void init() {
		setLayout(new BorderLayout());
		add(BorderLayout.CENTER, new Player(getParameter("file")));
	}
}