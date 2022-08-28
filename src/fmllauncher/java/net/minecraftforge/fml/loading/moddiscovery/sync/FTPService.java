package net.minecraftforge.fml.loading.moddiscovery.sync;

import net.minecraftforge.fml.loading.FMLConfig;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.net.SocketException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static net.minecraftforge.fml.loading.LogMarkers.SCAN;

public class FTPService {

    private static final Logger LOGGER = LogManager.getLogger();
    private static FTPService Instance;
    FTPClient ftpClient;
    private String _server;
    private String _user;
    private String _pass;
    private int _port;
    private String _serverModPath;
    private String _clientModPath;

    private FTPService (){
        this._server = FMLConfig.lktFtpServer();
        this._user = FMLConfig.lktFtpUser();
        this._pass = FMLConfig.lktFtpPass();
        this._port = FMLConfig.lktFtpPort();
        this._serverModPath = FMLConfig.lktFtpServerModPath();
        this._clientModPath = FMLConfig.lktFtpClientModPath();
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

    private void validateCredentials() throws IOException{
        if(_server == null || _user == null || _serverModPath == null)
            throw new IOException("Invalid parameters check the fml.toml file");
    }

    public List<Path> listMods() {
        List<Path> mods = new ArrayList<>();
        try {
            validateCredentials();
            this.connect();

            FTPFile[] fServer = ftpClient.listFiles(_serverModPath);
            FTPFile[] fClient = ftpClient.listFiles(_clientModPath);

            for(FTPFile file : fServer){
                if(file.isFile())
                    mods.add(Paths.get(_serverModPath,file.getName()));
            }

            for(FTPFile file : fClient){
                if(file.isFile())
                    mods.add(Paths.get(_clientModPath,file.getName()));
            }

            this.disconnect();
        }catch (IOException ex){
            LOGGER.error(SCAN,"Error listing remote mods");
            LOGGER.error(SCAN, ex.getMessage());

        }

        return mods;
    }


    public boolean downloadMod(Path modPath, String target){
        boolean success = false;
        try {
            this.validateCredentials();
            this.connect();
            ftpClient.setFileType(FTP.BINARY_FILE_TYPE);
            String remoteFile = modPath.toString().replace("\\","/");
            String localFile = Paths.get(target,modPath.getFileName().toString()).toString();

            OutputStream os = new BufferedOutputStream(new FileOutputStream(localFile));
            success = ftpClient.retrieveFile(remoteFile, os);

            os.flush();
            os.close();

            if(!success){
                String reply = ftpClient.getReplyString();
                LOGGER.error(SCAN,"Error downloading mod file {}...",remoteFile);
                LOGGER.error(SCAN,reply);
            }

            this.disconnect();

        }catch (IOException ex){
            LOGGER.error(SCAN,"Error downloading mod");
            LOGGER.error(SCAN, ex.getMessage());
            success = false;
        }

        return success;
    }
}
