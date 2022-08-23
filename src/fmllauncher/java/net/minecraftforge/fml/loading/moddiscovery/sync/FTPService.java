package net.minecraftforge.fml.loading.moddiscovery.sync;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;

import java.io.*;
import java.net.SocketException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class FTPService {
    private static FTPService Instance;
    FTPClient ftpClient;
    private String _server;
    private String _user;
    private String _pass;
    private int _port;
    private String _serverModPath;

    private FTPService (){
        this._server = System.getenv("lkt-ftp-server");
        this._user = System.getenv("lkt-ftp-user");
        this._pass = System.getenv("lkt-ftp-pass");
        this._port = System.getenv("lkt-ftp-port") == null ? 21 : Integer.parseInt(System.getenv("lkt-ftp-port"));
        this._serverModPath = System.getenv("lkt-ftp-mod-path") == null ? "/mods" : System.getenv("lkt-ftp-mod-path");
    }

    public static FTPService getInstance(){

        if(Instance == null)
            Instance = new FTPService();

        return Instance;
    }

    private void connect() throws IOException{
        if( ftpClient == null)
            ftpClient = new FTPClient();
        if(ftpClient.isConnected()) return;
        ftpClient.connect(_server, _port);
        ftpClient.login(_user, _pass);
    }


    private void disconnect() throws IOException{
        if( ftpClient == null)
            return;
        ftpClient.disconnect();
    }

    public List<String> listMods() throws IOException{
        List<String> mods = new ArrayList<>();

        this.connect();

        FTPFile[] files = ftpClient.listFiles(_serverModPath);

        for(FTPFile file : files){
            mods.add(file.getName());
        }

        this.disconnect();

        return mods;
    }


    public boolean downloadMod(String modName, String target){
        boolean success = false;
        try {
            this.connect();
            ftpClient.setFileType(FTP.BINARY_FILE_TYPE);
            String remoteFile = Paths.get(_serverModPath,modName).toString();
            String localFile = Paths.get(target,modName).toString();

            OutputStream outputStream1 = new BufferedOutputStream(new FileOutputStream(localFile));
            success = ftpClient.retrieveFile(remoteFile, outputStream1);
            outputStream1.close();

            this.disconnect();

        }catch (IOException ex){
            success = false;
        }

        return success;
    }
}
