package de.koze.mqttcsv.umsetzung;

import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Consumer;
import com.rabbitmq.client.DeliverCallback;
import com.rabbitmq.client.ExceptionHandler;
import com.rabbitmq.client.ShutdownListener;
import com.rabbitmq.client.ShutdownSignalException;
import com.rabbitmq.client.TopologyRecoveryException;
import com.rabbitmq.client.Connection;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.rabbitmq.client.Channel;

/*
 * 
 * In der Mappings.csv werden die verschiedenen Maschinen sowie deren Sensorwerte benannt
 * Format: machinenname, wert1, wert2, wert3, wert4, wert5... (maximal 9 Werte pro Sensor möglich)
 * 
 */

public class App {

	public static Properties props;
	
	private static String QUEUE_NAME_RECEIVE = "";
    private static String EXCHANGE_NAME = "";
    private static String TOPIC_RECEIVE = "";
    private static String HOST =  "";
    private static int PORT = 5672;
    private static String USER = "";
    private static String PW = "";
    private static String vHost = "";
    private static boolean autoDelete = true; 
    
    static BufferedWriter writer = null;
    static int currentLines = 0;
    static int maxLines = 350000;

    static Logger debugLogger;
    static Logger infoLogger;
    static Logger errorLogger;
    static Logger mdeLogger;
    static Logger mqttLogger;
    
    static ArrayList<ArrayList<String>> description = new ArrayList<ArrayList<String>>();
	
    public static void main(String[] args) {

    	//Logger Init
    	infoLogger = LoggerFactory.getLogger(App.class);
        debugLogger = LoggerFactory.getLogger("requestLogger");
        errorLogger = LoggerFactory.getLogger("errorLogger");
        mdeLogger = LoggerFactory.getLogger("mdeLogger");
        mqttLogger = LoggerFactory.getLogger("mqttLogger");
        
    	//Load Config File
    	try {
			System.out.println("Using config.properties configuration");
			props = new Properties();
			props.load(new FileInputStream("config.properties"));
			System.out.println("Configuration file loaded");
		} catch (FileNotFoundException e1) {
			System.out.println("No config.properties file found!");
			e1.printStackTrace();
			return;
		} catch (IOException e1) {
			System.out.println("Could not access config.properties file!");
			e1.printStackTrace();
			return;
		}

		try {
			QUEUE_NAME_RECEIVE = String.valueOf(props.getProperty("queue"));
			EXCHANGE_NAME = String.valueOf(props.getProperty("exchange"));
			TOPIC_RECEIVE = String.valueOf(props.getProperty("topic"));
			HOST = String.valueOf(props.getProperty("host"));
			PORT = Integer.valueOf(props.getProperty("port"));
			USER = String.valueOf(props.getProperty("user"));
			PW = String.valueOf(props.getProperty("password"));
			vHost = String.valueOf(props.getProperty("vhost"));
			autoDelete = Boolean.valueOf(props.getProperty("durable"));
			maxLines = Integer.valueOf(props.getProperty("maxlinesincsv"));
			System.out.println("Configuration applied");
		} catch (Exception e) {
			System.out.println("Configuration file incomplete!");
			e.printStackTrace();
			return;
		}
        
    	//Set Broker Configuration
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost(HOST);
        factory.setUsername(USER);
        factory.setPassword(PW);
        factory.setPort(PORT);
        factory.setVirtualHost(vHost);
        factory.setAutomaticRecoveryEnabled(true);
        factory.setConnectionTimeout(30000);
        factory.setHandshakeTimeout(30000);
        factory.setNetworkRecoveryInterval(10000);
        factory.setExceptionHandler(new ExceptionHandler() {

            public void handleUnexpectedConnectionDriverException(Connection conn, Throwable exception) {
            	errorLogger.error(" - RABBITMQ ERROR1 on " + conn.getAddress() + " - " + exception.fillInStackTrace());
            }

            public void handleTopologyRecoveryException(Connection conn, Channel ch,
                TopologyRecoveryException exception) {
            	errorLogger.error(" - RABBITMQ ERROR2 on " + conn.getAddress() + " - " + exception.fillInStackTrace());
            }

            public void handleReturnListenerException(Channel channel, Throwable exception) {
            	errorLogger.error(" - RABBITMQ ERROR3 on " + channel.getConnection().getAddress() + " - " +
                    exception.fillInStackTrace());
            }

            public void handleConsumerException(Channel channel, Throwable exception, Consumer consumer,
                String consumerTag, String methodName) {
            	errorLogger.error(" - RABBITMQ ERROR4 on " + channel.getConnection().getAddress() + " - " +
                        exception.fillInStackTrace());
            }

            public void handleConnectionRecoveryException(Connection conn, Throwable exception) {
            	errorLogger.error(" - RABBITMQ ERROR5 on " + conn.getAddress() + " - " +
                    exception.fillInStackTrace());
            }

            public void handleConfirmListenerException(Channel channel, Throwable exception) {
            	errorLogger.error(" - RABBITMQ ERROR6 on " + channel.getConnection().getAddress() + " - " +
                    exception.fillInStackTrace());
            }

            public void handleChannelRecoveryException(Channel channel, Throwable exception) {
            	errorLogger.error(" - RABBITMQ ERROR7 on " + channel.getConnection().getAddress() + " - " +
                    exception.fillInStackTrace());
            }

            public void handleBlockedListenerException(Connection conn, Throwable exception) {
            	errorLogger.error(" - RABBITMQ ERROR8 on " + conn.getAddress() + " - " +
                    exception.fillInStackTrace());
            }
        });
        
        
        //Init CSV File
        try {
        	File directory = new File("data");
            if (! directory.exists()){
                directory.mkdir();
            }
			writer = new BufferedWriter(new FileWriter("data/dataStart"+System.currentTimeMillis()+".csv"));
			writer.write("Source;Timestamp;Signalindex;Status;Description\n");
		} catch (IOException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
		}
        
        //Connect to Broker
        createMapping();
        connectMQTT(factory);
  
    }
    
    //Connect and Receive Messages
    private static void connectMQTT(ConnectionFactory factory) {
    	Connection connection;
        try {
            connection = factory.newConnection();
            Channel channel = connection.createChannel();
            channel.queueDeclare(QUEUE_NAME_RECEIVE, !autoDelete, false, autoDelete, null);
            channel.queueBind(QUEUE_NAME_RECEIVE, EXCHANGE_NAME, TOPIC_RECEIVE);
            channel.addShutdownListener(new ShutdownListener() {
                public void shutdownCompleted(ShutdownSignalException cause) {
                	errorLogger.error(" - Broker Connection Lost " + cause.getMessage() + " - " +
                        cause.fillInStackTrace());
                }
            });
            infoLogger.info(" - Connected to AMQP");
            debugLogger.debug(" - Connected to AMQP");
            DeliverCallback deliverCallback = (consumerTag, delivery) -> {
                String message = new String(delivery.getBody(), "UTF-8");
                JsonParser parser = new JsonParser();
                JsonObject o = parser.parse(message).getAsJsonObject();
            
                //Check if message is from IoT Gateway Box
                if (o.get("FormatId").toString().replace("\"", "").equals("TagValues")) {
	                	mqttLogger.info(new Date()+"," + message);
	                	debugLogger.debug(" - Message Received: " + message);
	                	processMSG(channel, message);
                }
            };
            channel.basicConsume(QUEUE_NAME_RECEIVE, true, deliverCallback, consumerTag -> {});
        } catch (Exception e) {
        	errorLogger.error(" - Error in opening Connection: " + e);
        	try {
        		Thread.sleep(300000);
        		connectMQTT(factory);
        	}  catch (Exception ex) {
        		errorLogger.error(" - Error in Wait: " + ex);
        	}
        }
    }

    //Process Message from IoT Gateway Box
    private static void processMSG(Channel channel, String message) { 
        JsonParser parser = new JsonParser();
        JsonObject o = parser.parse(message).getAsJsonObject();
        int sizeOfEvents = (o.get("TagData").getAsJsonArray()).size(); //Multiple Messages in one Message Object
        
        //Check if a new File should be created
        if(currentLines > maxLines) {
        	currentLines = 0;
        	try {
				writer.close();
				writer = new BufferedWriter(new FileWriter("data/dataStart"+System.currentTimeMillis()+".csv"));
				writer.write("Source;Timestamp;Signalindex;Status;Description\n"); //CSV Header
			} catch (IOException e) {
				e.printStackTrace();
			}
        }
        
        //Go through all messages in the object
        for(int i = 0;i<sizeOfEvents;i++) { //Für Alle Tag Data
    		JsonObject jObject = (o.get("TagData").getAsJsonArray()).get(i).getAsJsonObject();
    		JsonObject jObjectBackup = jObject;
            jObject = jObject.get("Values").getAsJsonObject(); //Get Values Object
            
            DateTime date = null;
            String dateString = "";
            try {
	            date = ISODateTimeFormat.dateTime().parseDateTime(jObjectBackup.get("Time").getAsString());
	            DateTimeFormatter dtf = DateTimeFormat.forPattern("yyyyMMddHHmmssSSSS");
	            dateString = dtf.print(date);	            
            } catch (Exception e) {
            	e.printStackTrace();
            	errorLogger.error(e+"");
            }
            
            Set < Map.Entry < String, JsonElement >> entries = jObject.entrySet();
            for (Map.Entry < String, JsonElement > entry: entries) { 
	            try {
	            	if(date != null) {
		            		String[] parts = entry.getKey().split("_");
		            		String statusDescription = getMapping(Integer.valueOf(parts[2]),Integer.valueOf(parts[4]));
		            		String machineName = getMachineMapping(Integer.valueOf(parts[2]));
		            		System.out.println(machineName + ";" + dateString + ";" + parts[3]+"_"+parts[4]+";"+jObject.get(entry.getKey()).getAsBoolean()+";"+statusDescription);
		            		writer.write(machineName + ";" + dateString + ";" + parts[3]+"_"+parts[4]+";"+jObject.get(entry.getKey()).getAsBoolean()+";"+statusDescription+"\n");
		            		currentLines++;
	                }
	            } catch (Exception e) {
	            	e.printStackTrace();
	            	errorLogger.error(e+"");
	            }
            } //All Values end
        } //All Tag Data end
        
    }
    
    //Create Description
    private static void createMapping() {
    	try {
    		FileReader in = new FileReader("mappings.csv");
    		Iterable<CSVRecord> records = CSVFormat.EXCEL.parse(in);
    		for (CSVRecord record : records) {
    			ArrayList<String> descriptionSet = new ArrayList<String>();
    			for(int i = 0;i<10;i++) {
    				try {
    					descriptionSet.add(record.get(i));
    				} catch (IndexOutOfBoundsException e) {
    					descriptionSet.add("");
    				}
    			}
    			description.add(descriptionSet);
    		}
		    in.close();
		} catch (Exception e) {
			System.out.println("Error in CSV Parser"+e);
		}
    }
    
    //Get Mappings for Machines
    private static String getMachineMapping(int machinenID) {
    	String mappingName = "";
    	try {
    		mappingName = description.get(machinenID-1).get(0);
    	} catch (Exception e) {
    		System.out.println("Error in getMachineMapping "+ e);
    	}
    	return mappingName;
    }
    
    //Get Mappings for Values
    private static String getMapping(int machinenID, int statusID) {
    	String mappingName = "";
    	try {
    		mappingName = description.get(machinenID-1).get(statusID);
    	} catch (Exception e) {
    		System.out.println("Error in getMapping "+ e);
    	}
    	return mappingName;
    }
}



