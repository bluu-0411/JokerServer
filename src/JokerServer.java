import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;

public class JokerServer {
    public static final int NAME = 0;
    public static final int COMMAND = 1;
    public static final int SETTING = 2;
    public static final int PUZZLE = 3;
    //more and more for different purposes
    public static final int PLAYER_INFO = 4;
    public static final int START_GAME = 5;
    public static final int UNDO = 6;
    public static final int UPLOAD_PUZZLE = 7;
    public static final int GET_TOP10 = 8;


    public static Object lockObject = new Object();


    //HashMap<Socket, DataOutputStream> clients = new HashMap<>();
    HashMap<Socket, Player> clients = new HashMap<>();


    RoomManager roomManager = new RoomManager();

    public JokerServer(int tcpPort) throws IOException {
        ServerSocket srvSocket = new ServerSocket(tcpPort);

        while(true){
            Socket socket=srvSocket.accept();

            Player player = new Player();

            synchronized (lockObject){
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                player.out = out;
                //clients.put(socket, out);
                clients.put(socket, player);
            }

            Thread t = new Thread(()->{
                try {
                    serve(player, socket);
                }catch (IOException ex){
                    System.out.println("Disconnected..."+socket.getInetAddress());
                    synchronized (lockObject){
                        clients.remove(socket);
                    }
                    roomManager.removePlayer(player);
                }
            });
            t.start();
        }
    }

    public void serve(Player player, Socket socket) throws IOException{
        DataInputStream in = new DataInputStream(socket.getInputStream());
        GameRoom room = roomManager.assign(player);

        boolean success = false;
        synchronized (room.lock) {
            success = sendPuzzle(player, room.engine.isGameOver);
        }

        if(!success)
            throw new IOException("Unable to send the puzzle to the new player!");

        byte[] buffer = new byte[1024];

        while(true){

            int dataType = in.readInt();

            int len = in.readInt();
            in.read(buffer, 0, len);

            switch(dataType){
                case NAME:
                    handleName(player, new String(buffer, 0, len));
                    break;
                case COMMAND:
                    handleCommand(player, new String(buffer,0,len));
                    break;
                case START_GAME:
                    handleStartGame(player);
                    break;
                case UNDO:
                    handleUndo(player);
                    break;
                case UPLOAD_PUZZLE:
                    handleUpload(player, in);
                    break;
                case GET_TOP10:
                    handleTop10(player);
                    break;
                default:
                    System.out.println("Unknown data type");
            }

            System.out.println("Receive " + dataType + ": " + new String(buffer, 0, len));
        }
    }

    public boolean sendPuzzle(Player player, boolean isGameOver){
        boolean success = true;

            try {
                //DataOutputStream out = clients.get(socket);
                //Player player = clients.get(socket);
                DataOutputStream out = player.out;
                out.writeInt(PUZZLE);// the type of data I am going to send

                System.out.println("Send puzzle data");

                out.writeInt(player.room.engine.SIZE);// the width of the puzzle
                out.writeInt(player.room.engine.SIZE);// the height of the puzzle
                for (int i = 0; i < player.room.engine.SIZE * player.room.engine.SIZE; i++) {
                    out.writeInt(player.room.engine.board[i]);
                }

                out.writeBoolean(isGameOver);
                out.flush();//force data send

                System.out.println("Send Completed" + Arrays.toString(player.room.engine.board));

            }catch (IOException ex){
                success = false;
            }

        return success;
    }

    public void handleCommand(Player player, String command){
        System.out.println("The player wants to run the command: " + command);
        if(player.room==null)
            return;
        synchronized (player.room.lock) {
            if(!player.room.started){
                System.out.println("Room: "+player.room.roomId + " not started");
                return;
            }
            Player currentPlayer = player.room.players.get(player.room.currentPlayer);

            if(currentPlayer != player){
                System.out.println("It is not your turn!");
                return;
            }
            int[] boardBeforeMove = Arrays.copyOf(player.room.engine.board, player.room.engine.board.length);
            boolean changed = player.room.engine.processCommand(player,command);
            if(!changed){
                System.out.println("Not changed!");
                if(player.room.engine.checkGameOver()){
                    System.out.println("Game Over!");
                    settleGame(player.room);
                }
                return;
            }
            if(player.room.engine.checkGameOver()){
                System.out.println("Game Over!");
                settleGame(player.room);
            }
            player.room.lastBoard = boardBeforeMove;
//            player.room.engine.processCommand(player, command);//process the command and update the data in board
            currentPlayer.room.moveLeft--;
            if(currentPlayer.room.moveLeft==0){
                player.room.currentPlayer = (player.room.currentPlayer+1)%player.room.players.size();
                player.room.moveLeft=4;
                Player nextPlayer = player.room.players.get(player.room.currentPlayer);
                System.out.println(nextPlayer.name+ " It is your turn!");
            }
            System.out.println(Arrays.toString(player.room.engine.board));

            ///send the updated puzzle data to other players in the same room
            for (Player p: player.room.players){
                if(sendPuzzle(p, player.room.engine.isGameOver)) {
                    sendPlayerInfo(player, p);
                }
            }
        }
    }
    public void sendPlayerInfo(Player player, Player otherPlayer){
        try {
            //Player otherPlayer = clients.get(socket);
            otherPlayer.out.writeInt(PLAYER_INFO);
            /// send the player name too!
            byte[] nameBytes = player.name ==null? new byte[0]: player.name.getBytes();
            otherPlayer.out.writeInt(nameBytes.length);
            otherPlayer.out.write(nameBytes);
            otherPlayer.out.writeInt(player.score);
            otherPlayer.out.flush();
        } catch (IOException ex){
            System.out.println("Cannot send player info, but i don't remove the player info from the map");
        }
    }

    public void handleName(Player player, String name){
        System.out.println("The player wants to set their name to: [" +name+"]");
        synchronized (lockObject) {
            player.name = name;
            sendPlayerInfo(player, player);
        }
    }

    public static void main(String[] args) throws IOException{
        new JokerServer(12345);
    }

    public void handleStartGame(Player player){
        if(player.room==null)
            return;
        synchronized (player.room.lock){
            if(player.room.host!=player){
                System.out.println("Only host can start the game!");
                return;
            }
            if(player.room.started){
                System.out.println("Game already started!");
                return;
            }
            player.room.started=true;
            System.out.println("Room " + player.room.roomId + " started by " + player.name);
        }
    }
    public void handleUndo(Player player) {
        if (player.room == null || !player.room.started)
            return;
        synchronized (player.room.lock) {
            Player currentPlayer = player.room.players.get(player.room.currentPlayer);
            if (!(player == currentPlayer)) {
                System.out.println("It is not your turn!");
                return;
            }
            if (player.hasUndone) {
                System.out.println("You have already used your undo!");
                return;
            }
            if (player.room.lastBoard == null) {
                System.out.println("No action to undo!");
                return;
            }
            player.room.engine.board = Arrays.copyOf(player.room.lastBoard, player.room.lastBoard.length);
            player.hasUndone = true;
            player.room.moveLeft++;
            player.room.lastBoard = null;
            System.out.println(player.name + " has used undo this turn!");

            for (Player p : player.room.players) {
                if (sendPuzzle(p, player.room.engine.isGameOver)) {
                    sendPlayerInfo(player, p);
                }
            }

        }

    }
    public void handleUpload(Player player, DataInputStream in){
        if(player.room==null || !player.room.started){
            return;
        }
        synchronized (player.room.lock){
            try{
                int [] newBoard = new int[16];
                for(int i=0; i<16; i++){
                    newBoard[i] = in.readInt();
                }
                Player currentPlayer = player.room.players.get(player.room.currentPlayer);
                if(currentPlayer!=player){
                    System.out.println("It is not your turn!");
                    return;
                }
                player.room.engine.board = newBoard;
                player.room.moveLeft--;
                if(player.room.moveLeft == 0){
                    player.room.currentPlayer = (player.room.currentPlayer+1)%player.room.players.size();
                    player.room.moveLeft=4;
                    System.out.println(player.room.players.get(player.room.currentPlayer).name+" it's your turn!");
                    player.room.lastBoard = null;
                }
                for(Player p: player.room.players){
                    if(sendPuzzle(p, player.room.engine.isGameOver)) {
                        sendPlayerInfo(player, p);
                    }
                }
            } catch (IOException e) {
                System.out.println("Fail to upload the board!");
            }
        }
    }
    public void settleGame(GameRoom room){
        if(room.isRecordSaved){
            return;
        }
        room.isRecordSaved = true;
        Player winner = room.players.get(0);
        for(Player p: room.players){
            if(p.score>winner.score){
                winner = p;
            }
        }
        System.out.println("Winner: "+winner.name + " Score: "+winner.score + " Level: "+ room.engine.level);
    }
    public void handleTop10(Player player){
        //别忘了替换，这里需要data.base.getScores()，和排行榜！！！！！！！！！！！！！

    }
}
