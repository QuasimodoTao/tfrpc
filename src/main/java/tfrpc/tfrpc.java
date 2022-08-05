package tfrpc;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;

class Pack {
	public static final int TYPE_HANDSHAKE = 1;
	public static final int TYPE_KEEPALIVE = 2;
	public static final int TYPE_START_CONNECT = 3;
	public static final int TYPE_STOP_CONNECT = 4;
	public static final int TYPE_DATA = 5;
	public static final int TYPE_DISCONNECT = 6;
	public int dataSize = 0;
	public int type = 0;
	public int readPos = 0;
	public byte data[] = null;
	public int readShort() {
		if(readPos >= dataSize) return 0;
		if(data == null) return 0;
		int res = data[readPos];
		readPos++;
		int res2 = data[readPos];
		readPos++;
		res &= 0xff;
		res2 <<= 8;
		res |= res2;
		res &= 0xffff;
		return res;
	}
}

public class tfrpc {
	static class KeepaliveThread extends Thread{public void run() {tfrpc.keepalive();}}
	static class Local2ServerThread extends Thread{
		private int secssion;
		public Local2ServerThread(int secssion) {this.secssion = secssion;}
		public void run() {tfrpc.local2Server(secssion);}
	}
	
	public static final int DEF_SERVER_PORT = 18086;
	public static final int DEF_DEST_PORT = 22;
	public static final int DEF_MAP_PORT = 28086;
	public static final long KEEPALIVE_THREHOLD = 1000*30;
	public static final String DEF_SERVER_IP = "127.0.0.1";
	public static final int DEF_MAX_LINK = 10;
	
	public static Socket server = null;
	public static Socket local[] = new Socket[32];
	public static InputStream serveri = null;
	public static OutputStream servero = null;
	public static InputStream locali[] = new InputStream[32];
	public static OutputStream localo[] = new OutputStream[32];
	private static AtomicInteger serverWriteLock = new AtomicInteger(0);
	public static volatile long prevTime;
	public static Thread keepaliveThread = null;
	public static Thread local2ServerThread[] = new Thread[32];
	
	public static int destPort = DEF_DEST_PORT;
	public static int mapPort = DEF_MAP_PORT;
	public static int serverPort = DEF_SERVER_PORT;
	public static String serverIP = DEF_SERVER_IP;
	public static int maxLink = DEF_MAX_LINK;
	
	static int byte2Int(byte s[]) {
		if(s.length == 0) return 0;
		int s0 = 0;
		int s1 = 0;
		int s2 = 0;
		int s3 = 0;
		switch(s.length) {
		default:
		case 4:
			s3 = s[3];
		case 3:
			s2 = s[2];
		case 2:
			s1 = s[1];
		case 1:
			s0 = s[0];
		}
		s3 <<= 24;
		s2 <<= 16;
		s1 <<= 8;
		s0 &= 0xff;
		s1 &= 0xff00;
		s2 &= 0xff0000;
		s3 &= 0xff000000;
		return s0 | s1 | s2 | s3;
	}
	static void int2Byte(byte s[],int val) {
		if(s.length == 0) return;
		switch(s.length) {
		default:
		case 4:
			s[3] = (byte)(val >> 24);
		case 3:
			s[2] = (byte)(val >> 16);
		case 2:
			s[1] = (byte)(val >> 8);
		case 1:
			s[0] = (byte)val;
		}
	}
	
	public static void throwCloseConnectPack(String reason) throws Exception {
		Pack cp = new Pack();
		cp.type = Pack.TYPE_DISCONNECT;
		byte data[] = reason.getBytes();
		cp.dataSize = data.length;
		cp.data = data;
		writePackToServer(cp);
	}	
	private static void readDataFromServer(byte buf[]) throws Exception{
		int cur = 0;
		while(cur < buf.length) {
			int ret = serveri.read(buf,cur,buf.length - cur);
			if(ret < 0) throw new Exception("Connect interrupt.");
			cur += ret;
		}
	}
	private static Pack readPackFromServer() throws Exception {
		byte lenb[] = new byte[2];
		byte type[] = new byte[1];
		Pack res = new Pack();
		readDataFromServer(lenb);
		int len = byte2Int(lenb);
		readDataFromServer(type);
		res.type = type[0];
		res.type &= 0xff;
		if(len == 0) return res;
		res.data = new byte[len];
		readDataFromServer(res.data);
		res.dataSize = len;
		return res;
	}
	private static void writePackToServer(Pack p) throws Exception {
		try {
			byte total[] = new byte[3 + p.dataSize];
			int2Byte(total,p.dataSize);
			total[2] = (byte)(p.type);
			for(int i = 0;i < p.dataSize;i++) total[i + 3] = p.data[i];
			serverWriteLock.compareAndExchange(0, 1);
			if(server.isClosed() == true) {
				serverWriteLock.set(0);
				throw new Exception("Connect interrupt.");
			}
			servero.write(total,0,3 + p.dataSize);
			servero.flush();
			serverWriteLock.set(0);
		} catch (IOException e) {
			throw e;
		}
	}
	static boolean hanshake(){
		try {
			Pack p = new Pack();
			p.dataSize = 5;
			p.type = Pack.TYPE_HANDSHAKE;
			byte port[] = new byte[3];
			int2Byte(port,mapPort);
			port[2] = (byte)maxLink;
			byte data[] = new byte[5];
			data[0] = 0x00;
			data[1] = 0x02;
			data[2] = port[0];
			data[3] = port[1];
			data[4] = port[2];
			p.data = data;
			writePackToServer(p);
			Pack p2 = readPackFromServer();
			if((p2.type != Pack.TYPE_HANDSHAKE) || 
				(p2.dataSize != p.dataSize) || 
				(p2.data[0] != p.data[0]) || 
				(p2.data[1] != p.data[1]) ||
				(p2.data[3] != p.data[3])) {
				System.out.println("Handshake fail.");
				if(p2.type == Pack.TYPE_DISCONNECT) {
					String reason = new String(p.data);
					System.out.println("Connect interrupt:" + reason);
				}
				server.close();
				return false;
			}
			System.out.println("Handshake success.");
			return true;
		} catch(Exception e) {
			System.out.println("Handshake fail.");
			return false;
		}
	}
	
	public static void keepalive() {
		System.out.println("Keepalive thread is running.");
		prevTime = System.currentTimeMillis();
		Pack p = new Pack();
		p.type = Pack.TYPE_KEEPALIVE;
		int loop = 0;
		try {
			while(true) {
				Thread.sleep(1000);
				if(System.currentTimeMillis() - prevTime >= tfrpc.KEEPALIVE_THREHOLD) {
					System.out.println("Connect out-of-time");
					server.close();
					break;
				}
				if(loop++ >= 6) {//Send keepalive per 6-seconds
					loop = 0;
					writePackToServer(p);
				}
			}
		}catch(Exception e) {}
		System.out.println("Keepalive thread is stop.");
	}
	public static void local2Server(int secssion) {
		System.out.println("Local to server thread is running.");
		byte data[] = new byte[4097];
		data[0] = (byte)secssion;
		Pack p = new Pack();
		p.data = data;
		p.type = Pack.TYPE_DATA;
		try {
			while(true) {
				int avilable = locali[secssion].read(data,1,4096);
				if(avilable < 0) break;
				p.dataSize = avilable + 1;
				writePackToServer(p);
			}
		}catch(Exception e) {}
		Pack dp = new Pack();
		dp.type = Pack.TYPE_STOP_CONNECT;
		dp.data = new byte[1];
		dp.dataSize = 1;
		dp.data[0] = (byte)secssion;
		try {
			writePackToServer(dp);
		} catch (Exception e) {}
		try {
			locali[secssion].close();
			locali[secssion] = null;
		} catch(Exception e3) {
			e3.printStackTrace();
		}
		System.out.println("Local to server thread is stop.");
	}
	public static void server2Local() {
		System.out.println("Server to local thread is running.");
		try {
			while(true) {
				Pack p = readPackFromServer();
				if(p.type == Pack.TYPE_KEEPALIVE) prevTime = System.currentTimeMillis();//must update keepalive time
				else if(p.type == Pack.TYPE_DATA) {
					int secssion = p.data[0] & 0x1f;
					localo[secssion].write(p.data,1,p.dataSize - 1);//if fail mean server is on error
				}
				else if(p.type == Pack.TYPE_STOP_CONNECT) {//stop connect mean control stop connect,local can not be write
					int secssion = p.data[0] & 0x1f;
					System.out.println("Secssion " + secssion + " is stop by server.");
					try {
						localo[secssion].close();
						local[secssion].shutdownOutput();
						local[secssion].close();
					} catch(Exception e) {}
				}
				else if(p.type == Pack.TYPE_START_CONNECT) {//connect from control
					int secssion = p.data[0] & 0x1f;
					System.out.println("Start secssion " + secssion + ".");
					try {
						local[secssion] = new Socket("127.0.0.1",destPort);
						localo[secssion] = local[secssion].getOutputStream();
						locali[secssion] = local[secssion].getInputStream();
						local2ServerThread[secssion] = new Thread(()->{local2Server(secssion);});
						local2ServerThread[secssion].start();
					}
					catch(Exception e2) {
						Pack dp = new Pack();
						dp.type = Pack.TYPE_STOP_CONNECT;
						dp.data = new byte[1];
						dp.data[0] = (byte)secssion;
						dp.dataSize = 1;
						writePackToServer(dp);
					}
				}
				else if(p.type == Pack.TYPE_DISCONNECT) {//disconnect pack from server.
					System.out.println("Connection from:" + server.getInetAddress().getHostAddress() + " be close by client." + new String(p.data));
					break;
				}
				else {//recive unknow pack type,mean server is on error
					throwCloseConnectPack("Recive a unknow pack, type:" + p.type + ".");
					System.out.println("Recive a unknow pack, type:" + p.type + ".");
					break;
				}
			}
		} catch(Exception e) {
			e.printStackTrace();
		}
		keepaliveThread.interrupt();
		for(int i = 0;i < 32;i++) if(local2ServerThread[i] != null) local2ServerThread[i].interrupt();
		try {
			server.shutdownInput();
			server.shutdownOutput();
			server.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		System.out.println("Server to local thread is stop.");
	}
	
	public static void exe() throws InterruptedException{
		System.out.println("TFRPC: Server " + serverIP + ":" + serverPort + ".");
		System.out.println("TFRPC: Map port " + destPort + " to " + mapPort);
		while(true) {
			try {
				server = new Socket(serverIP,serverPort);
				serveri = server.getInputStream();
				servero = server.getOutputStream();
			}catch(Exception e){
				System.out.println("Connect server fail.");
				Thread.sleep(10000);
				continue;
			}
			if(hanshake() == false) {
				Thread.sleep(10000);
				continue;
			}
			keepaliveThread = new Thread(()->{keepalive();});
			keepaliveThread.start();
			Thread s2l = new Thread(()->{server2Local();});
			s2l.start();
			try {
				s2l.join();
				
			} catch(InterruptedException e) {
				try {
					server.close();
				} catch (Exception e2) {}
				throw e;
			}
			try {
				server.close();
			} catch (Exception e) {
				Thread.sleep(10000);
			}
		}
	}
	
	public static void main(String argv[]) {
		//tfrpc dest_port map_port max_connection server_ip server_port
		if(argv.length != 0) {
			switch(argv.length) {
			default:
			case 5:
				serverPort = Integer.parseInt(argv[4]);
				if(serverPort == 0) serverPort = DEF_SERVER_PORT;
			case 4:
				serverIP = argv[3];
			case 3:
				maxLink = Integer.parseInt(argv[2]);
				if(maxLink == 0) maxLink = DEF_MAX_LINK;
				if(maxLink > 32) {
					System.out.println("Can not support link more than 32.");
					return;
				}
			case 2:
				mapPort = Integer.parseInt(argv[1]);
				if(mapPort == 0) mapPort = DEF_MAP_PORT;
			case 1:
				destPort = Integer.parseInt(argv[0]);
				if(destPort == 0) destPort = DEF_DEST_PORT;
			}
		}
		else {
			System.out.println("tfrpc localPort mapPort maxLink serverIp serverPort");
			//return;
		}
		try {
			exe();
		} catch (InterruptedException e) {
			System.exit(0);
		}
	}
}
