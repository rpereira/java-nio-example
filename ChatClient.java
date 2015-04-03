import java.awt.BorderLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Scanner;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;


public class ChatClient {
	
    JFrame frame = new JFrame("Chat Client");
    private JTextField chatBox = new JTextField();
    private JTextArea chatArea = new JTextArea();
    
    static private BufferedReader input;
    static private DataOutputStream output;
    static private Socket socket;
	static private Scanner scanner;
    
    /**
     * Appends a string to the text box.
     * 
     * @param message - the message to append
     */
    public void printMessage(final String message) {
    	
        chatArea.append(message);
    }

    /**
     * Class constructor.
     * 
     * @param server
     * @param port
     */
    public ChatClient(String server, int port) throws IOException {
    	
        /*
         * Initialize graphic interface.
         */
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        JPanel panel = new JPanel();
        panel.setLayout(new BorderLayout());
        panel.add(chatBox);
        frame.setLayout(new BorderLayout());
        frame.add(panel, BorderLayout.SOUTH);
        frame.add(new JScrollPane(chatArea), BorderLayout.CENTER);
        frame.setSize(500, 300);
        frame.setVisible(true);
        chatArea.setEditable(false);
        chatBox.setEditable(true);
        chatBox.addActionListener(new ActionListener() {
        	
            @Override
            public void actionPerformed(ActionEvent e) {
            	
                try {
                	
                	String _message = chatBox.getText();
                	
                	if(_message != null && _message.length() > 0) {
                		
                		newMessage(_message);
                	}                    
                    
                } catch (IOException ex) { } 
                finally
                {
                   chatBox.setText("");
                }
            }
        });
        
        try {
            
            ChatClient.socket = new Socket(server, port);
            ChatClient.output = new DataOutputStream(socket.getOutputStream());
            ChatClient.input  = new BufferedReader(new InputStreamReader(socket.getInputStream(), "UTF-8"));
            
        } catch(UnknownHostException e) {
        	
        	System.out.println("Connection error: " + e);
    	}
    }

    /**
     * Called every time a message is sent.
     * 
     * @param message - the input message
     */
    public void newMessage(String message) throws IOException {
    	
    	message = message + "\n";
    	
    	output.write(message.getBytes("UTF-8"));
    }

    public void run() throws IOException {
    	
		try {
			
			String message
	    	, type = "";
    	
	    	while((message = input.readLine()) != null) {
	    		
	    		scanner = new Scanner(message);	            
	    		type    = scanner.next();
	            	
	            switch(type) {
	            
	                case "OK":
	                    printMessage("- OK.\n");
	                    break;
	                    
	                case "ERROR":	             
	                	
	                    printMessage("- An error occurred.\n");
	                    break;
	                    	                      
	                case "NEWNICK":
	                    printMessage("- " + scanner.next() + " changed his/her name to " + scanner.next() + "\n");
	                    break;
	                    
	                case "JOINED":
	                    printMessage("- " + scanner.next() + " has joined the room.\n");
	                    break;
	                    
	                case "LEFT":
	                    printMessage("- " + scanner.next() + " has left the room.\n");
	                    break;
	                    
	                case "BYE":	             
	                	
	                    printMessage("Leaving chat... Bye!\n");
	                    break;
	                    
	                case "MESSAGE": {
	                	String _sender = scanner.next();
	                    
	                    printMessage(_sender + ":" + scanner.nextLine() + "\n");
	                    break;
	                }
	                    
	                case "PRIVATE": {
	                	String _sender = scanner.next();
	                	
	                	printMessage("- Private message from " + _sender + ":" + scanner.nextLine() + "\n");
	                	break;
	                }
	            }
	        }
	    	
		} catch(Exception e) {
			
			e.printStackTrace();
		}
    	
        frame.dispose();
    }
    
    public static void main(String[] args) throws IOException {
    	
        ChatClient client = new ChatClient(args[0], Integer.parseInt(args[1]));
        client.run();
    }
}
