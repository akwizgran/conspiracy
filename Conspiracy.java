import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Scanner;
import java.util.TreeMap;

public class Conspiracy {

	public static final int PORT = 53591, MAX_ID = 1000 * 1000;
	public static final int MIN_B = 1000, MAX_B = 10000;

	private static final String FILENAME = "conspiracy.html";

	private final File dir;
	private final ServerSocket ss;
	private final Collection<Listener> listeners = new ArrayList<Listener>();

	private byte[] code = null;

	public Conspiracy(File dir) throws IOException {
		this.dir = dir;
		ss = new ServerSocket();
	}

	public void run() {
		try {
			code = load(new File(dir, FILENAME));
			ss.bind(getSocketAddress());
			while(true) new ClientHandler(ss.accept()).start();
		} catch(IOException e) {
			e.printStackTrace();
		}
	}

	private byte[] load(File f) throws IOException {
		FileInputStream in = new FileInputStream(f);
		byte[] b = new byte[(int) f.length()];
		try {
			int offset = 0;
			while(offset < b.length) {
				int read = in.read(b, offset, b.length - offset);
				if(read == -1) throw new EOFException();
				offset += read;
			}
		} finally {
			in.close();
		}
		return b;
	}

	private SocketAddress getSocketAddress() throws UnknownHostException {
		InetAddress address = InetAddress.getByName("0.0.0.0");
		return new InetSocketAddress(address, PORT);
	}

	public static void main(String[] args) throws IOException {
		File dir;
		if(args.length == 1) dir = new File(args[0]);
		else dir = new File(".");
		new Conspiracy(dir).run();
	}

	private class ClientHandler extends Thread {

		final Socket s;

		ClientHandler(Socket s) {
			this.s = s;
		}

		@Override
		public void run() {
			try {
				Scanner in = new Scanner(s.getInputStream());
				PrintStream out = new PrintStream(s.getOutputStream());
				while(in.hasNextLine()) {
					String request = in.nextLine();
					try {
						while(in.hasNextLine()) {
							if(in.nextLine().isEmpty()) {
								Response response = parseRequest(request);
								response.send(out);
								break;
							}
						}
					} catch(IllegalArgumentException e) {
						e.printStackTrace();
						break;
					}
				}
				s.close();
			} catch(IOException e) {
				e.printStackTrace();
			}
		}

		Response parseRequest(String request) {
			String url = request.replaceFirst("GET (.*) HTTP/1\\..", "$1");
			if(url.equals(request)) throw new IllegalArgumentException();
			if(url.equals("/")) return new Response(true, code, "text/html");
			if(!url.startsWith("/?")) return new Response(false);
			Map<String, String> m = new TreeMap<String, String>();
			for(String param : url.replace("/?", "").split("&")) {
				int equals = param.indexOf('=');
				if(equals == -1) throw new IllegalArgumentException();
				String key = param.substring(0, equals);
				String value = param.substring(equals + 1);
				m.put(key, value);
			}
			if(m.containsKey("i")) {
				int id = getInt(m.get("id"), 0, MAX_ID);
				float y = getFloat(m.get("i"), 0f, 1f);
				int b = getInt(m.get("b"), MIN_B, MAX_B);
				synchronized(listeners) {
					for(Listener l : listeners) l.handleEvent(id, y, b, true);
					listeners.clear();
				}
				return new Response(true);
			} else if(m.containsKey("e")) {
				int id = getInt(m.get("id"), 0, MAX_ID);
				float y = getFloat(m.get("e"), 0f, 1f);
				int b = getInt(m.get("b"), MIN_B, MAX_B);
				synchronized(listeners) {
					for(Listener l : listeners) l.handleEvent(id, y, b, false);
					listeners.clear();
				}
				return new Response(true);
			} else if(m.containsKey("p")) {
				Listener l = new Listener();
				synchronized(listeners) {
					listeners.add(l);
				}
				return new Response(true, l.waitForEvent(), "text/xml");
			} else {
				throw new IllegalArgumentException();
			}
		}

		int getInt(String s, int min, int max) {
			if(s == null) throw new IllegalArgumentException("null");
			try {
				int i = Integer.parseInt(s);
				if(i < min || i > max) throw new IllegalArgumentException(s);
				return i;
			} catch(NumberFormatException e) {
				throw new IllegalArgumentException(s);
			}
		}

		float getFloat(String s, float min, float max) {
			if(s == null) throw new IllegalArgumentException("null");
			try {
				float f = Float.parseFloat(s);
				if(f < min || f > max) throw new IllegalArgumentException(s);
				return f;
			} catch(NumberFormatException e) {
				throw new IllegalArgumentException(s);
			}
		}
	}

	private static class Response {

		final boolean ok;
		final byte[] content;
		final String type;

		Response(boolean ok) {
			this.ok = ok;
			content = new byte[0];
			type = "text/plain";
		}

		Response(boolean ok, byte[] content, String type) {
			this.ok = ok;
			this.content = content;
			this.type = type;
		}

		void send(PrintStream out) throws IOException {
			out.print("HTTP/1.1 " + (ok ? "200 OK" : "404 Not Found") + "\r\n");
			out.print("Content-Length: " + content.length + "\r\n");
			out.print("Content-Type: " + type + "\r\n");
			out.print("\r\n");
			out.write(content);
			out.flush();
		}
	}

	private static class Listener {

		private int id = 0, b = 0;
		private float y = 0f;
		private boolean inhale = false, eventReceived = false;

		synchronized void handleEvent(int id, float y, int b, boolean inhale) {
			this.id = id;
			this.y = y;
			this.b = b;
			this.inhale = inhale;
			eventReceived = true;
			notifyAll();
		}

		synchronized byte[] waitForEvent() {
			while(!eventReceived) {
				try {
					wait();
				} catch(InterruptedException e) {
					e.printStackTrace();
				}
			}
			String xml = "<?xml version='1.0' encoding='utf-8'?>" +
			"<event>" + "<id>" + id + "</id>" + "<y>" + y + "</y>" +
			"<b>" + b + "</b>" + "<in>" + inhale + "</in>" + "</event>";
			try {
				return xml.getBytes("UTF-8");
			} catch(UnsupportedEncodingException e) {
				e.printStackTrace();
				throw new RuntimeException(e);
			}
		}
	}
}
