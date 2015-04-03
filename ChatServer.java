import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

interface Constants {

	/*
	 * Protocol commands.
	 */
	static final String NICK    = "/nick";
	static final String JOIN    = "/join";
	static final String LEAVE   = "/leave";
	static final String BYE     = "/bye";
	static final String PRIVATE = "/priv";
	
	/*
	 * Response messages.
	 */
	static final String OK      = "OK\n";
	static final String ERROR   = "ERROR\n";
	
	static final String NEW_LINE = System.getProperty("line.separator");
}

public class ChatServer implements Constants {
	
	// A pre-allocated buffer for the received data
	static private final ByteBuffer buffer = ByteBuffer.allocate(16384);

	// Decoder for incoming text -- assume UTF-8
	static private final Charset charset = Charset.forName("UTF8");
	static private final CharsetDecoder decoder = charset.newDecoder();
	
	private static HashMap<SocketChannel, Client> clients 		   = new HashMap<>();
	private static HashMap<String, ArrayList<SocketChannel>> rooms = new HashMap<>();

	private static Scanner command_scanner;	

	/*
	 * Client's class.
	 */
	static private class Client {
		
		public String nick;
		public State  state;
		public String current_room;
		public StringBuffer buffer;

		public Client() {
			this.nick 		  = null;
			this.state 		  = State.INIT;
			this.current_room = null;
			this.buffer       = new StringBuffer();
		}
	}

	/*
	 * Client' state information.
	 */
	private enum State {
		INIT,
		OUTSIDE, 
		INSIDE
	}

	static public void main(String args[]) throws Exception {
		
		// Parse port from command line
        int port = Integer.parseInt(args[0]);

        try {
	        // Instead of creating a ServerSocket, create a ServerSocketChannel
	        ServerSocketChannel ssc = ServerSocketChannel.open();
	
	        // Set it to non-blocking, so we can use select
	        ssc.configureBlocking(false);
	
	        // Get the Socket connected to this channel, and bind it to the listening port
	        ServerSocket ss 	  = ssc.socket();
	        InetSocketAddress isa = new InetSocketAddress(port);
	        ss.bind(isa);
	
	        // Create a new Selector for selecting
	        Selector selector = Selector.open();
	
	        // Register the ServerSocketChannel, so we can listen for incoming connections
	        ssc.register(selector, SelectionKey.OP_ACCEPT);
	        System.out.println("Listening on port " + port);
	
	        while(true) {
	            // See if we've had any activity -- either an incoming connection,
	            // or incoming data on an existing connection
	            int ready_channels = selector.select();
	
	            // If we don't have any activity, loop around and wait again
	            if(ready_channels == 0) {
	                continue;
	            }
	
	            // Get the keys corresponding to the activity that has been
	            // detected, and process them one by one
	            Set<SelectionKey> selected_keys = selector.selectedKeys();
	            
	            Iterator<SelectionKey> key_iterator = selected_keys.iterator();
	            
	            while(key_iterator.hasNext()) {
	            	
	                // Get a key representing one of bits of I/O activity
	                SelectionKey key = (SelectionKey) key_iterator.next();
	
	                // What kind of activity is it?
	                if (key.isAcceptable()) {
	                	
	                    // It's an incoming connection.  Register this socket with
	                    // the Selector so we can listen for input on it
	                    Socket s = ss.accept();
	                    System.out.println("Got connection from " + s);
	                    
	                    // Make sure to make it non-blocking, so we can use a selector on it.
	                    SocketChannel sc = s.getChannel();
	                    sc.configureBlocking(false);
	
	                    // Register it with the selector, for reading
	                    sc.register(selector, SelectionKey.OP_READ);
	                    
	                    // Create a new Client object
	                    clients.put(sc, new Client());
	                    	                    
	                } else if (key.isReadable()) {
	
	                    SocketChannel sc = null;
	
	                    try {
	                        // It's incoming data on a connection -- process it
	                        sc = (SocketChannel) key.channel();
	                        
	                        boolean ok = processInput(sc);
	
	                        // If the connection is dead, remove it from the selector and close it
	                        if(!ok) {
	                        	
	                            key.cancel();
	                            
	                            Socket s = null;	                            
	                            try {
	                                                       	
	                                s = sc.socket();
	                                
	                                Client client = clients.get(sc);
	                                
	                                if(client.state == State.INSIDE) {
	                                	
	                    				leave(sc, client);
	                    			}
	                                
	                                System.out.println("Closing connection to " + s);
	                                clients.remove(sc);
	                                s.close();
	                                
	                            } catch(IOException ie) {
	                            	
	                                System.err.println("Error closing socket " + s + ": " + ie);
	                            }
	                        }
	                        
	                    } catch(IOException ie) {
	                        // On exception, remove this channel from the selector
	                        key.cancel();
	
	                        try {
	                        	
	                            sc.close();
	                            clients.remove(sc);
	                            
	                        } catch(IOException ie2) {
	                        	
	                            System.out.println(ie2);
	                        }
	
	                        System.out.println("Closed " + sc);
	                    }
	                }
	            }
	
				// We remove the selected_keys and key_iterator, because we've dealt with them.
	            selected_keys.clear();
			}
	        
		} catch (IOException ie) {

			System.err.println(ie);
		}
	}

	// Just read the message from the socket and send it to stdout
	static private boolean processInput(SocketChannel sc) throws IOException {
		
		// make buffer ready for writing
		buffer.clear();
		
		// Read the message to the buffer
		sc.read(buffer);
		buffer.flip();

		// If no data, close the connection
		if (buffer.limit() == 0) {
			
			return false;
		}
		
		// Decode and print the message to stdout		
		String chunk = decoder.decode(buffer).toString();
				
		// Create a new buffer for the connection
		Client client = clients.get(sc);		
		
		StringBuffer command = client.buffer;
		
		// Buffering until a line termination is received
		if(!chunk.endsWith("\n")) {
			
			client.buffer.append(chunk);
								
		} else {
			
			command.append(chunk);
			chunk = command.toString();
			command = new StringBuffer();
			client.buffer = command;
	    
		    // if it is a command
		    if(chunk.charAt(0) == '/' && chunk.charAt(1) != '/') {
		    	
		    	command_scanner = new Scanner(chunk);
		    	
		    	String command_type = command_scanner.next();
		    	
		    	switch(command_type) {
		    	
		    		case NICK:
		    			
		    			if(!command_scanner.hasNext()) {
		    				
		    				sendStatusMessage(sc, ERROR);	// argument nickname is missing
		    				break;
		    			}
		    			
		    			// read specified nickname
		    			nick(sc, client, command_scanner.next());
		    			break;
		    			
	    			case JOIN:
	    				
						if(!command_scanner.hasNext()) {
		    				
		    				sendStatusMessage(sc, ERROR);	// argument room_name is missing
		    				break;
		    			}
		    			
	    				// read chat room name
		    			join(sc, client, command_scanner.next());
		    			break;
		    			
	    			case LEAVE:
	    				
	    				if(command_scanner.hasNext()) {
		    				
		    				sendStatusMessage(sc, ERROR);	// too much arguments
		    				break;
		    			}
	    				
	    				leave(sc, client);
	    				break;
	    				
					case BYE:
	    				
						if(command_scanner.hasNext()) {
		    				
		    				sendStatusMessage(sc, ERROR);	// too much arguments
		    				break;
		    			}

	    				bye(sc, client);
	    				break;
	    				
					case PRIVATE:
						
						if(!command_scanner.hasNext()) {
		    				
		    				sendStatusMessage(sc, ERROR);	// recipient is missing
		    				break;
		    			}
						
						String recipient = command_scanner.next();
						
						if(!command_scanner.hasNextLine()) {
		    				
		    				sendStatusMessage(sc, ERROR);	// message content is missing
		    				break;
		    			}
						
						chunk = command_scanner.nextLine().trim();
	    				
	    				sendPrivateMessage(sc, client, recipient, chunk.toString());
	    				break;
	    				
					default:
						
						sendStatusMessage(sc, ERROR);		// not a valid command
						break;
		    	}
		    	
		    } else {		// not a command; is a regular message
		    	
		    	if(chunk.charAt(0) == '/') {
		    		
		    		chunk = chunk.substring(1);
		    	}
		    	
		    	broadcastMessage(sc, client, chunk);
		    }
		}

		return true;
	}
	
	
	/*****************************************************
	 * SEND MESSAGES
	 *****************************************************/
	
	/**
     * Sends a status message to the client.
     * 
     * @param sc	  - the socket channel 
     * @param message - the status message
     */
    static void sendStatusMessage(SocketChannel sc, String message) throws IOException {
    	
        ByteBuffer bb = ByteBuffer.wrap(message.getBytes());
        sc.write(bb);
    }
	
    /**
     * Sends a message to all socket_channels in an array, except sender.
     * 
     * @param socket_channels - all socket channels
     * @param message		  - the message to send
     * @param sender		  - the client who sent the message
     */
    static void notifyOthers(ArrayList<SocketChannel> socket_channels, String message, SocketChannel sender) throws IOException {    	
    	
        for(SocketChannel sc : socket_channels) {
        	
        	ByteBuffer bb = ByteBuffer.wrap(message.getBytes());
        	
            if(sc != sender) {
                sc.write(bb);
            }
        }
    }
    
    /**
	 * Broadcasts a message inside the sender's current room.
	 * 
	 * @param sc	  - the socket channel
	 * @param client  - the user who sends the message
	 * @param message - the message content
	 */
	static void broadcastMessage(SocketChannel sc, Client client, String message) throws IOException {
		
        if(client.state != State.INSIDE) {
        	
            sendStatusMessage(sc, ERROR);	// client.state must be INSIDE
            
        } else {
        	        
        	// Note that "message" already contains a "\n", so there's no need to send NEW_LINE.
        	notifyOthers(rooms.get(client.current_room), "MESSAGE " + client.nick + " " + message, null);
        }
    }
	
	
	/*****************************************************
	 * PROTOCOL COMMANDS
	 *****************************************************/
	
    /**
     * Selects or chooses a new name, which must be unique for each client.
     * 
     * INIT -> /nick name && !available(name) -> ERROR -> INIT
     * INIT -> /nick name && available(name)  -> OK    -> OUTSIDE
     * 
     * OUTSIDE -> /nick name && !available(name) -> ERROR -> OUTSIDE
     * OUTSIDE -> /nick name && available(name)  -> OK    -> OUTSIDE
     * 
     * INSIDE -> /nick name && !available(name) -> ERROR -> INSIDE
     * 
     * INSIDE -> /nick name && !available(name) 
     * 		  -> OK to client
     * 		  -> NEWNICK old_nick new_nick to others
     * 		  -> INSIDE
     * 
     * @param sc	   - the socket channel
	 * @param client   - the user who wants to set his/her nickname
     * @param new_name - the specified new nickname
     */
	static void nick(SocketChannel sc, Client client, String new_nickname) throws IOException {
		
		// check if nickname is already in use
		for(Map.Entry<SocketChannel, Client> entry : clients.entrySet()) {
			
			if(new_nickname.equals(entry.getValue().nick)) {
				
				sendStatusMessage(sc, ERROR);	// nickname already in use
				return;
			}
		}
		
		if(client.state == State.INIT) {
			
            client.state = State.OUTSIDE;
            
        } else if(client.state == State.INSIDE) {
        	
        	notifyOthers(rooms.get(client.current_room), "NEWNICK " + client.nick +
        				 " " + new_nickname + NEW_LINE, sc);
        }
        
        client.nick = new_nickname;
        sendStatusMessage(sc, OK);
    }
	
	/**
	 * Removes the specified client from his/her current chat room (does not set user' state to OUTSIDE.
	 * 
	 * @param sc	 - the socket channel
	 * @param client - the user who wants to leave his/her current room
	 */
	private static void _leaveRoom(SocketChannel sc, Client client) throws IOException {
		
		rooms.get(client.current_room).remove(sc);
        notifyOthers(rooms.get(client.current_room), "LEFT " + client.nick + NEW_LINE, null);
	}
	
	/**
	 * Enter or change a chat room. If the specified name doesn't exist, it should be created.
	 * 
	 * OUTSIDE and /join room -> INSIDE
	 * * 'OK' to user; 
	 * * 'JOINED name' to others inside the new room.
	 * 
	 * INSIDE and /join room -> INSIDE
	 * * 'OK' to user; 
	 * * 'JOINED name' to others inside the new room; 
	 * * 'LEFT name' to others inside the old room.
	 * 
	 * @param sc	 	- the socket channel
	 * @param client 	- the user who wants to join the specified chat room
	 * @param room_name - the requested chat room
	 */
	static void join(SocketChannel sc, Client client, String room_name) throws IOException {
		
		if(client.state == State.INIT) {
        	
        	sendStatusMessage(sc, ERROR);	// nickname is not defined yet
            return;
        }
        
        // If client is INSIDE a room, leave it. No need to temporarily set client.state to OUTSIDE.
        if(client.state == State.INSIDE) {
        	
        	_leaveRoom(sc, client);
        }

        // Join the new room
        ArrayList<SocketChannel> room_users = rooms.get(room_name);

        // If room doesn't exist, create it
        if(room_users == null) {
        	
            room_users = new ArrayList<>();
            rooms.put(room_name, room_users);
            
        } else {
        	
        	notifyOthers(room_users, "JOINED " + client.nick + NEW_LINE, null);
        }

        room_users.add(sc);
        
        client.current_room = room_name;
        client.state        = State.INSIDE;

        sendStatusMessage(sc, OK);
    }

	/**
	 * Removes the client from his/her current chat room.
	 * Leave current room.
	 * 
	 * @param sc	 - the socket channel
	 * @param client - the user who wants to leave his/her current room
	 */
	static void leave(SocketChannel sc, Client client) throws IOException {
		
        if(client.state == State.INIT) {
        	
            sendStatusMessage(sc, ERROR);	// nickname is not defined yet
            return;
        }
        
        if(client.state == State.INSIDE) {
        	
        	_leaveRoom(sc, client);
        	client.state = State.OUTSIDE;
            sendStatusMessage(sc, OK);
            
        } else {
        	
            sendStatusMessage(sc, ERROR); // client.state must be INSIDE
        }
    }
	
	/**
	 * Closes the server connection with the specified client.
	 * 
	 * @param sc	 - the socket channel
	 * @param client - the user who wants to close the connection
	 */
	static void bye(SocketChannel sc, Client client) throws IOException {
		
		Socket s = sc.socket();
		
        if(client.state == State.INSIDE) {
        	
        	_leaveRoom(sc, client);
        	client.state = State.OUTSIDE;
        }
                   
        System.out.println("Closing connection to " + s);
        
        sendStatusMessage(sc, "BYE\n");
        clients.remove(sc);
        s.close();
    }
	
	/**
	 * Sends a private message from 'client' to 'recipient'. If 'recipient' does not exist,
	 * 'ERROR' should be sent to 'client'. Otherwise, if 'recipient' does exist, 'OK? should be sent
	 * to 'client', and the 'message' should sent to 'recipient'
	 * 
	 * @param sc		- the socket channel
	 * @param client	- the user who sends the private message
	 * @param recipient	- the target user
	 * @param message	- the private message to be sent
	 */
	static void sendPrivateMessage(SocketChannel sc, Client client, String recipient, String message) throws IOException {
		
        if(client.state == State.INIT) {
        	
            sendStatusMessage(sc, ERROR);	// nickname is not defined yet
            return;
        }
        
        SocketChannel recipient_sc = null;
        
        //Find target user socket channel
        for(Map.Entry<SocketChannel, Client> entry : clients.entrySet()) {
        	
            if(recipient.equals(entry.getValue().nick)) {
            	
            	recipient_sc = entry.getKey();
                break;
            }
        }
        
        if(recipient_sc == null) {
        	
        	sendStatusMessage(sc, ERROR);	// recipient nickname does not exist
        	
        } else {
        	
        	// send the private message to it's recipient
        	sendStatusMessage(recipient_sc, "PRIVATE " + client.nick + " " + message + NEW_LINE);
        	
        	// notify sender
            sendStatusMessage(sc, OK);
        }
    }
}
