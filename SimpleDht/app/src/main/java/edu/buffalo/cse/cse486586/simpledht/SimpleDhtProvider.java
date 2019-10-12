package edu.buffalo.cse.cse486586.simpledht;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Formatter;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.widget.ProgressBar;

import org.w3c.dom.Node;

public class SimpleDhtProvider extends ContentProvider {
    static final String TAG = SimpleDhtProvider.class.getSimpleName();
    static final String REMOTE_PORT0 = "11108";
    static final String REMOTE_PORT1 = "11112";
    static final String REMOTE_PORT2 = "11116";
    static final String REMOTE_PORT3 = "11120";
    static final String REMOTE_PORT4 = "11124";
    static final int SERVER_PORT = 10000;
    static String successor = null;
    static String predecessor = null;
    static String thisPortStr = null;
    static String hashMyPort = "";  // to store hash value of this port
    // TreeMap to store hashes and its corresponding keys in a sorted manner
    TreeMap<String, String> messageMap = new TreeMap<String, String>();
    // List of ports present in the chord
    ArrayList<String> portList = new ArrayList<String>();

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        // TODO Auto-generated method stub
        Log.d(TAG, "In delete() function");
        int delCount = 0;
        try {
            File directory = getContext().getFilesDir();
            if (!selection.equals("*") && !selection.equals("@")){
                Log.d(TAG, "Only one node in the chord");
                File file = new File(directory, selection);
                // Reference to delete a file: https://www.geeksforgeeks.org/delete-file-using-java/
                if (file.delete())
                    delCount += 1;
            }else if(selection.equals("@")){
                Log.d(TAG,"Deleting all files from this AVD");
                delCount = deleteAll();
            }else if (selection.equals("*")){
                Log.d(TAG, "Deleting all files from all AVDs");
                for (String port : portList){
                    Log.d(TAG, "Deleting all messages from port: "+port);
                    try {
                        Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                                Integer.parseInt(port)*2);
                        // Send delete message to destPort
                        String msgToBeSent = "Delete All|"+"\n";
                        PrintWriter out = new PrintWriter(socket.getOutputStream());
                        out.write(msgToBeSent);
                        out.flush();
                        // Read message back after deleting all
                        BufferedReader in = new BufferedReader(
                                new InputStreamReader(socket.getInputStream()));
                        String str = in.readLine();
                        Log.d(TAG, "String received after deleting all files: "+str);
                        if (str.contains("Number of files deleted")){
                            String[] splitString = str.split("\\|");
                            delCount += Integer.parseInt(splitString[1]);
                            socket.close();
                        }
                    }catch (IOException ioe){
                        Log.e(TAG, "IO Exception while deleting *");
                        ioe.printStackTrace();
                    }
                }
            }
        }catch (Exception e){
            Log.e(TAG, "Exception found in delete()");
            e.printStackTrace();
        }
        return delCount;
    }

    public int deleteAll(){
        /*
        * Responsible for delete all messages in the current AVD
        * */
        int tempCount = 0;
        Log.d(TAG,"In deleteAll of port: "+thisPortStr);
        try{
            File directory = getContext().getFilesDir();
            File[] files = directory.listFiles();
            for (File file : files) {
                if(file.delete())
                    tempCount += 1;
            }
        }catch (Exception e){
            Log.e(TAG, "Exception found in deleteAll()");
            e.printStackTrace();
        }
        return tempCount;
    }

    @Override
    public String getType(Uri uri) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        /*
         * References:
         * Line 76: https://stackoverflow.com/questions/2390244/how-to-get-the-keys-from-contentvalues
         * Lines 81-83: https://developer.android.com/training/data-storage/files#WriteInternalStorage
         * Line 81: https://stackoverflow.com/questions/28242386/cannot-resolve-method-openfileoutputjava-lang-string-int
         */
        String filename = (String)values.get("key");
        String fileContents = (String)values.get("value");
        String destPort = null;
        // starting port is thisPortStr
        try {
            if (predecessor == null && successor == null) {
                Log.d(TAG, "Since predecessor and successor is null, inserting in this node");
                insertIntoFile(filename, fileContents);
                return uri;
            }else {
                String key_hash = genHash(filename);
                int lastChordIndex = portList.size()-1;
                if (key_hash.compareTo(genHash(predecessor)) > 0 && key_hash.compareTo(genHash(thisPortStr)) <= 0){
                    Log.d(TAG, "Inserting in the port: "+thisPortStr);
                    insertIntoFile(filename, fileContents);
                    return uri;
                }
                else if (key_hash.compareTo(genHash(portList.get(0))) <= 0){
                    Log.d(TAG, "Smaller than 1st node's hash, so insert in the first node");
                    if (portList.get(0).equals(thisPortStr)){
                        // Insert in this node itself
                        insertIntoFile(filename, fileContents);
                        return uri;
                    }else {
                        destPort = String.valueOf(Integer.parseInt(portList.get(0))*2);
                    }
                }
                else if (key_hash.compareTo(genHash(portList.get(lastChordIndex))) > 0){
                    Log.d(TAG,"Key's hash is larger than last node's hash, so insert in the first node");
                    if (portList.get(0).equals(thisPortStr)) {
                        // Insert in this node itself
                        insertIntoFile(filename, fileContents);
                        return uri;
                    }else {
                        destPort = String.valueOf(Integer.parseInt(portList.get(0))*2);
                    }
                }else {
                    for (int i=1; i<portList.size(); i++){
                        if (key_hash.compareTo(genHash(portList.get(i-1))) > 0 &&
                                key_hash.compareTo(genHash(portList.get(i))) <= 0){
                            destPort = String.valueOf(Integer.parseInt(portList.get(i))*2);
                            break;
                        }
                    }
                }
                // connect to destPort and insert
                try{
                    Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                            Integer.parseInt(destPort));

                    // Send insert message to destPort
                    String msgToBeSent = "Insert|"+filename+"|"+fileContents+"\n";
                    PrintWriter out = new PrintWriter(socket.getOutputStream());
                    out.write(msgToBeSent);
                    out.flush();
                    // Read message back after insert
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(socket.getInputStream()));
                    String str = in.readLine();
                    if (str.equals("Insert Done")){
                        socket.close();
                        return uri;
                    }

                }catch (IOException ioe){
                    Log.e(TAG, "IO Exception in insert which creating socket");
                    ioe.printStackTrace();
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        Log.v("insert", values.toString());
        return uri;
    }

    // Common method to insert into file
    public void insertIntoFile(String filename, String fileContents){
        /*
        * This module contains the logic to insert key-value into a file
        * */
        Log.d(TAG, "Inserting into file");
        try {
            FileOutputStream outputStream;
            outputStream = getContext().openFileOutput(filename, Context.MODE_PRIVATE);
            outputStream.write(fileContents.getBytes());
            outputStream.close();
        }catch (FileNotFoundException f){
            Log.e(TAG,"File not found in insertIntoFile for filename: "+filename);
            f.printStackTrace();
        }catch (IOException ioe){
            Log.e(TAG,"IO Exception in insertIntoFile for filename: "+filename);
            ioe.printStackTrace();
        }
    }

    @Override
    public boolean onCreate() {
        // TODO Auto-generated method stub
        // Reference: From SimpleMessenger code
        TelephonyManager tel = (TelephonyManager) this.getContext().getSystemService(Context.TELEPHONY_SERVICE);
        String portStr = tel.getLine1Number().substring(tel.getLine1Number().length() - 4);
        final String myPort = String.valueOf((Integer.parseInt(portStr) * 2));
        thisPortStr = portStr;
        try {
            hashMyPort = genHash(portStr);
        }catch (NoSuchAlgorithmException nsae){
            Log.e(TAG, "No such algorithm exception in onCreate()");
            nsae.printStackTrace();
        }
        // Server Socket
        try {
            Log.d(TAG, "Trying to create ServerSocket object");
            ServerSocket serverSocket = new ServerSocket(SERVER_PORT);
            serverSocket.setReuseAddress(true);
            Log.d(TAG, "Hola in onCreate() of this AVD");
            new ServerTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, serverSocket);
        } catch (IOException e) {
            Log.e(TAG, "Printing stack trace");
            e.printStackTrace();
            Log.e(TAG, "Can't create a ServerSocket");
            return false;
        }
        String msg = "Activation Message";
        // Send message to 5554 to join the chord
        new ClientTask().executeOnExecutor(AsyncTask.SERIAL_EXECUTOR, msg, myPort);
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs,
            String sortOrder) {
        /*
         *  Sid notes:
         *  The ContentResolver object communicates with the provider object,
         *  an instance of a class that implements ContentProvider.
         *  Lines 128-133: https://developer.android.com/training/data-storage/files#WriteInternalStorage
         *  Line 138-141: https://stackoverflow.com/questions/18290864/create-a-cursor-from-hardcoded-array-instead-of-db
         */

        try {
            File directory = getContext().getFilesDir();
            // Matrix Cursor
            String[] columns = new String[]{"key", "value"};
            MatrixCursor cursor = new MatrixCursor(columns);
            Log.d(TAG, "Selection parameter is: "+selection);
            String portNum = "";
            System.out.println("Chord contents is: ");
            System.out.println(Arrays.toString(portList.toArray()));

            if (portList.size() <= 0 || predecessor == null || successor == null) {
                if (!selection.equals("*") && !selection.equals("@")) {
                    Log.d(TAG, "Only one node in the chord");
                    File file = new File(directory, selection);
                    FileInputStream f = new FileInputStream(file);
                    BufferedReader br = new BufferedReader(new InputStreamReader(f));
                    String ln = br.readLine();
                    Log.v(TAG, "Line Read: " + ln);
                    br.close();

                    String[] input = new String[]{selection, ln};
                    cursor.addRow(input);
                } else {
                    Log.d(TAG, "Fetching all files");
                    // Reference for fetching all the files:
                    // https://stackoverflow.com/a/8647397/10316954
                    File[] files = directory.listFiles();
                    for (File file : files) {
                        System.out.println("File is: ");
                        System.out.println(file);
                        FileInputStream f = new FileInputStream(file);
                        BufferedReader br = new BufferedReader(new InputStreamReader(f));
                        String ln = br.readLine();
                        Log.v(TAG, "Line Read: " + ln);
                        br.close();

                        String key_name = file.getName();
                        Log.d(TAG, "File name is: " + key_name);
                        String[] input = new String[]{key_name, ln};
                        cursor.addRow(input);
                    }
                }
            }
            else {
                if (selection.equals("@")){
                    String allFilesList = queryAllFilesInAVD();
                    String[] allFiles = allFilesList.split("-");
                    for (String file: allFiles){
                        String[] keyValPair = file.split("\\|");
                        String key = keyValPair[0];
                        String val = keyValPair[1];
                        String[] input = new String[]{key, val};
                        cursor.addRow(input);
                    }
                }
                else if (!selection.equals("*")){
                    // These conditions are similar to insert
                    String key_hash = genHash(selection);
                    int lastChordIndex = portList.size()-1;
                    if (key_hash.compareTo(genHash(predecessor)) > 0 &&
                            key_hash.compareTo(genHash(thisPortStr)) <= 0){
                        portNum = thisPortStr;
                    }
                    else if (key_hash.compareTo(genHash(portList.get(0))) <= 0){
                        Log.d(TAG, "Smaller than 1st node's hash, so query in the first node");
                        portNum = portList.get(0);
                    }
                    else if (key_hash.compareTo(genHash(portList.get(lastChordIndex))) > 0){
                        Log.d(TAG,"Key's hash is larger than last node's hash, so query in the first node");
                        portNum = portList.get(0);
                    }else {
                        for (int i=1; i<portList.size(); i++){
                            if (key_hash.compareTo(genHash(portList.get(i-1))) > 0 &&
                                    key_hash.compareTo(genHash(portList.get(i))) <= 0){
                                portNum = portList.get(i);
                                Log.d(TAG, "Correct port number to query is: "+portNum);
                                break;
                            }
                        }
                    }
                    // Get string for a single message
                    Log.d(TAG, "Requesting to query in port: "+portNum);
                    try{
                        Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                                Integer.parseInt(portNum)*2);
                        // Send query message to destPort
                        String msgToBeSent = "Query Message|"+selection+"\n";
                        PrintWriter out = new PrintWriter(socket.getOutputStream());
                        out.write(msgToBeSent);
                        out.flush();
                        // Read message back after querying all
                        BufferedReader in = new BufferedReader(
                                new InputStreamReader(socket.getInputStream()));
                        String str = in.readLine();
                        if (str.contains("One File")){
                            socket.close();
                            String val = str.split("::")[1];
                            String[] input = new String[]{selection, val};
                            cursor.addRow(input);
                        }
                    }catch (IOException ioe){
                        Log.e(TAG,"IO Exception in query()");
                        ioe.printStackTrace();
                    }
                }
                else if(selection.equals("*")){
                    for (String port : portList){
                        Log.d(TAG, "Querying all messages from port: "+port);
                        try {
                            Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                                    Integer.parseInt(port)*2);
                            // Send query message to destPort
                            String msgToBeSent = "Query All|"+"\n";
                            PrintWriter out = new PrintWriter(socket.getOutputStream());
                            out.write(msgToBeSent);
                            out.flush();
                            // Read message back after querying all
                            BufferedReader in = new BufferedReader(
                                    new InputStreamReader(socket.getInputStream()));
                            String str = in.readLine();
                            Log.d(TAG, "String received after querying all files: "+str);
                            if (str.contains("No files in this AVD")){
                                Log.d(TAG, "No files in this AVD so continue");
                                socket.close();
                            }
                            else if (str.contains("All files")){
                                socket.close();
                                String allFilesList = str.split("::")[1];
                                String[] allFiles = allFilesList.split("-");
                                for (String file: allFiles){
                                    String[] keyValPair = file.split("\\|");
                                    String key = keyValPair[0];
                                    String val = keyValPair[1];
                                    String[] input = new String[]{key, val};
                                    cursor.addRow(input);
                                }
                            }
                        }catch (IOException ioe){
                            Log.e(TAG, "IO Exception while querying *");
                            ioe.printStackTrace();
                        }
                    }
                }
            }
            return cursor;
        }
        catch (Exception e){
            Log.e(TAG, "File not Found or IOException while reading File");
        }

        Log.v("query", selection);
        return null;
    }

    // Common method to return one key, value pair as a string
    public String queryOneFile(String selection){
        /*
        Returns a string after querying a key
         */
        Log.d(TAG, "In queryOneFile");
        String keyValPair = "";
        try{
            File directory = getContext().getFilesDir();
            File file = new File(directory, selection);
            FileInputStream f = new FileInputStream(file);
            BufferedReader br = new BufferedReader(new InputStreamReader(f));
            String ln = br.readLine();
            Log.v(TAG, "Line Read: " + ln);
            br.close();
            // String to be returned
            keyValPair += ln;
        }catch (FileNotFoundException fnfe){
            Log.e(TAG, "File not found exception");
            fnfe.printStackTrace();
            return null;
        }catch (IOException ioe){
            Log.e(TAG, "IO Exception");
            ioe.printStackTrace();
            return null;
        }
        return keyValPair;
    }

    // Common method to return all key, value pairs in an AVD as a string
    public String queryAllFilesInAVD() {
        Log.d(TAG, "In queryAllFilesInAVD");
        String keyValPairs = "";
        try {
            File directory = getContext().getFilesDir();
            File[] files = directory.listFiles();
            for (File file : files) {
                System.out.println("File is: ");
                System.out.println(file);
                FileInputStream f = new FileInputStream(file);
                BufferedReader br = new BufferedReader(new InputStreamReader(f));
                String ln = br.readLine();
                Log.v(TAG, "Line Read: " + ln);
                br.close();

                String key_name = file.getName();
                Log.d(TAG, "File name is: " + key_name);
                keyValPairs += key_name+"|"+ln+"-";
            }
        }catch (FileNotFoundException fnfe){
            Log.e(TAG, "File not found exception");
            fnfe.printStackTrace();
            return null;
        }catch (IOException ioe){
            Log.e(TAG, "IO Exception");
            ioe.printStackTrace();
            return null;
        }
        Log.d(TAG, "Files being returned is: "+keyValPairs);
        return keyValPairs;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        // TODO Auto-generated method stub
        return 0;
    }

    private String genHash(String input) throws NoSuchAlgorithmException {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] sha1Hash = sha1.digest(input.getBytes());
        Formatter formatter = new Formatter();
        for (byte b : sha1Hash) {
            formatter.format("%02x", b);
        }
        return formatter.toString();
    }

    private class ServerTask extends AsyncTask<ServerSocket, String, Void> {
        @Override
        protected Void doInBackground(ServerSocket... sockets) {
            ServerSocket serverSocket = sockets[0];
            while (true){
                try {
                    Log.d(TAG,"In Server Socket");
                    // BufferedReader to receive messages
                    Socket socket = serverSocket.accept();
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(socket.getInputStream()));
                    String str = in.readLine();
                    Log.d(TAG, "String received in Server Socket is: "+str);

                    if (str.contains("Activate")){
                        Log.d(TAG, "In Activation logic of Server Socket");
                        String[] splitString = str.split("\\|");
                        messageMap.put(genHash(splitString[1]), splitString[1]);
                        // Reference for below lines: https://stackoverflow.com/a/29166313/10316954
                        SimpleDhtProvider outerObj = new SimpleDhtProvider();
                        NodeJoinTask obj = outerObj.new NodeJoinTask();
                        // Send acknowledgement
                        PrintWriter out = new PrintWriter(socket.getOutputStream());
                        out.write("acknowledge\n");
                        out.flush();
                        // Send the TreeMap to an AsyncTask called NodeJoinTask
                        obj.execute(messageMap);
//                        obj.cancel(true);
//                        outerObj.new NodeJoinTask().execute(messageMap);
                    }
                    else if (str.contains("Ordering")){
                        Log.d(TAG, "In ordering of ports");
                        String[] splitString = str.split("-");
                        String[] ports = splitString[1].split("\\|");
                        portList.clear();
                        // Add all elements into array list
                        for (int i=0; i < ports.length; i++){
                            portList.add(ports[i]);
                        }
                        Log.d(TAG, "Portlist obtained is: ");
                        System.out.println(Arrays.toString(portList.toArray()));

                        // Find predecessor and successor
                        int currIndex = portList.indexOf(thisPortStr);
                        int lastIndex = portList.size()-1;
                        if (lastIndex == 0){
                            predecessor = null;
                            successor = null;
                        }
                        else if (currIndex == 0) {
                            predecessor = portList.get(lastIndex);
                            successor = portList.get(1);
                        }
                        else if (currIndex == lastIndex){
                            predecessor = portList.get(currIndex-1);
                            successor = portList.get(0);
                        }
                        else {
                            predecessor = portList.get(currIndex-1);
                            successor = portList.get(currIndex+1);
                        }
                        Log.d(TAG, "Predecessor and successor of this node is: "+predecessor
                        +" and "+successor);

                        // Send acknowledgement
                        PrintWriter out = new PrintWriter(socket.getOutputStream());
                        out.write("acknowledge\n");
                        out.flush();
                    }else if (str.contains("Insert")){
                        Log.d(TAG,"Insert request");
                        String[] splitString = str.split("\\|");
                        insertIntoFile(splitString[1], splitString[2]);
                        // Send acknowledgement that insert is done
                        PrintWriter out = new PrintWriter(socket.getOutputStream());
                        out.write("Insert Done\n");
                        out.flush();
                    }
                    else if (str.contains("Query All")){
                        Log.d(TAG, "Query all request in port: "+thisPortStr);
                        String msg = queryAllFilesInAVD();
                        Log.d(TAG, "String of messages returned is: "+msg);
                        PrintWriter out = new PrintWriter(socket.getOutputStream());
                        if (msg.equals("")){
                            out.write("No files in this AVD"+"\n");
                        }
                        else {
                            out.write("All files::" + msg + "\n");
                        }
                        out.flush();
                    }
                    else if(str.contains("Query Message")){
                        String key = str.split("\\|")[1];
                        Log.d(TAG, "Query request for the message: "+key+" in port: "+thisPortStr);
                        String valReturned = queryOneFile(key);
                        Log.d(TAG, "Value returned in Server is: "+valReturned);
                        PrintWriter out = new PrintWriter(socket.getOutputStream());
                        out.write("One File::"+valReturned+"\n");
                        out.flush();
                    }
                    else if (str.contains("Delete All")){
                        Log.d(TAG, "In Delete All of Server Socket");
                        int ret = deleteAll();
                        String retVal = String.valueOf(ret);
                        Log.d(TAG, "Value returned from deleteAll(): "+retVal);
                        // Send it to client
                        PrintWriter out = new PrintWriter(socket.getOutputStream());
                        out.write("Number of files deleted|"+retVal+"\n");
                        out.flush();
                    }
                }catch (IOException ioe){
                    Log.e(TAG, "Receive message failed in ServerTask");
                    ioe.printStackTrace();
                }catch (NoSuchAlgorithmException nsae){
                    Log.e(TAG, "No Such Algo Exception in Server Task");
                    nsae.printStackTrace();
                }
            }
//            return null;
        }
    }

    private class ClientTask extends AsyncTask<String, Void, Void> {
        /*
        * Send message to 5554 in order to join the chord
        * */
        @Override
        protected Void doInBackground(String... msgs) {
            String msg = msgs[0];
            String portNum = msgs[1];
            String halvedPort = String.valueOf(Integer.parseInt(portNum)/2);
            try {
                if (msg.equals("Activation Message")){
                    Log.d(TAG, "In Client Task");
                    Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                            Integer.parseInt(REMOTE_PORT0));

                    // Send Activation message to 5554
                    String msgToBeSent = "Activate|"+halvedPort+"\n";
                    PrintWriter out = new PrintWriter(socket.getOutputStream());
                    out.write(msgToBeSent);
                    out.flush();

                    // Wait for acknowledgment
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(socket.getInputStream()));
                    String str = in.readLine();
                    if (str != null && str.equals("acknowledge")){
                        Log.d(TAG,"Closing socket in client task");
                        socket.close();
                    }
                }
            }catch (IOException ioe){
                Log.e(TAG, "IO Exception in Client Task");
                ioe.printStackTrace();
            }
            return null;
        }
    }
    private class NodeJoinTask extends AsyncTask<TreeMap<String, String>, Void, Void>{
        /*
         * This AsyncTask is responsible for sending the order of nodes to all the ports in the chord.
         */
        @Override
        protected Void doInBackground(TreeMap<String, String>... nodesMap) {
            Log.d(TAG, "In NodeJoinTask");
            Log.d(TAG, "TreeMap received is:");
            System.out.println(Collections.singletonList(nodesMap[0]));
            String msgToSend = "";
            for (TreeMap.Entry<String,String> entry : nodesMap[0].entrySet()){
                msgToSend += String.valueOf(Integer.parseInt(entry.getValue()))+"|";
            }
            try{
                for (TreeMap.Entry<String,String> entry : nodesMap[0].entrySet()){
                    Socket socket = new Socket(InetAddress.getByAddress(new byte[]{10, 0, 2, 2}),
                            Integer.parseInt(entry.getValue())*2);

                    // Send ordering of nodes to all ports
                    String msgToBeSent = "Ordering-"+msgToSend+"\n";
                    Log.d(TAG,"Sending ordering of ports: "+msgToBeSent);
                    PrintWriter out = new PrintWriter(socket.getOutputStream());
                    out.write(msgToBeSent);
                    out.flush();

                    // Wait for acknowledgment
                    BufferedReader in = new BufferedReader(
                            new InputStreamReader(socket.getInputStream()));
                    String str = in.readLine();
                    if (str.equals("acknowledge")){
                        Log.d(TAG,"Closing socket");
                        socket.close();
                    }
                }
            }catch (IOException ioe){
                Log.e(TAG, "IO Exception in NodeJoinTask");
            }
            return null;
        }
    }
}
