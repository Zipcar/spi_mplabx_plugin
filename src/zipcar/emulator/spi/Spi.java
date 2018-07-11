package zipcar.emulator.spi;

import com.microchip.mplab.mdbcore.simulator.Peripheral;
import com.microchip.mplab.mdbcore.simulator.SFR;
import com.microchip.mplab.mdbcore.simulator.SFRSet;
import com.microchip.mplab.mdbcore.simulator.scl.SCL;
import com.microchip.mplab.mdbcore.simulator.MessageHandler;
import com.microchip.mplab.mdbcore.simulator.SimulatorDataStore.SimulatorDataStore;
import com.microchip.mplab.mdbcore.simulator.PeripheralSet;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.util.LinkedList;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.Socket;
import org.openide.util.lookup.ServiceProvider;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

@ServiceProvider(path = Peripheral.REGISTRATION_PATH, service = Peripheral.class)
public class Spi implements Peripheral {

    String SPI_NUM; // SPI Name (eg: SPI1, SPI2, etc...)
    String SPI_BUFF; // Respective SPI BUFFER SFR
    String SPI_STAT; // SPI STAT buffer

    static Spi instance;
    SimulatorDataStore DS;
    MessageHandler messageHandler;
    SCL scl;

    SFR sfrBuff;
    SFR sfrStat;
    SFR sfrTX;
    SpiObserver spiMonitor;

    LinkedList<Byte> bytes = new LinkedList<Byte>();
    Socket reqSocket;
    BufferedOutputStream request;
    BufferedInputStream response;
    Yaml yaml = new Yaml();

    int updateCounter = 0;
    int cycleCount = 0;
    boolean notInitialized = true;
    long lastRead;
    long lastSent;
    boolean sendFlag = false;
    boolean injectedFlag = false;
    boolean systemBooted = false;
    String tempStr = "";
    FileInputStream conf;
    Map config;
    
    public Spi() {
        spiMonitor = new SpiObserver();
        yaml = new Yaml();
    }

    @Override
    public boolean init(SimulatorDataStore DS) {
        this.DS = DS;
        SFRSet sfrs;
        messageHandler = DS.getMessageHandler();

        // Initialize instance variables
        try {
            conf = new FileInputStream(new File("spiconfig.yml"));
            config = (Map) yaml.load(conf);
            SPI_NUM = config.get("spiNum").toString();
            SPI_BUFF = config.get("spiBuff").toString();
            SPI_STAT = config.get("spiStat").toString();
        } catch (FileNotFoundException e) {
            messageHandler.outputError(e);
            messageHandler.outputMessage("Are you sure you placed config.yml in the correct folder?");
            return false;
        } catch (SecurityException e) {
            messageHandler.outputError(e);
            return false;
        } catch (NullPointerException e) {
            messageHandler.outputError(e);
            messageHandler.outputMessage("Are you sure you have all of the necessary config fields?");
            return false;
        } catch (ClassCastException e) {
            messageHandler.outputError(e);
            return false;
        }

        sfrs = DS.getSFRSet();
        sfrBuff = sfrs.getSFR(SPI_BUFF);
        sfrStat = sfrs.getSFR(SPI_STAT);

        // Remove default SPI
        PeripheralSet periphSet = DS.getPeripheralSet();
        Peripheral spiPeriph = periphSet.getPeripheral(SPI_NUM);
        if (spiPeriph != null) {
            spiPeriph.deInit();
            periphSet.removePeripheral(spiPeriph);
        }
        
        // Setup Sockets
        if (!openSockets()) {
            return false;
        }

        // Add observers
        sfrBuff.addObserver(spiMonitor);

        messageHandler.outputMessage("External Peripheral Initialized: SPI");
    
        // Add peripheral to list and return true
        DS.getPeripheralSet().addToActivePeripheralList(this);
        return true;
    }

    @Override
    public void deInit() {
        try {
            request.close();
            response.close();
            DS.getPeripheralSet().removePeripheral(this);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void addObserver(PeripheralObserver observer) {

    }

    @Override
    public String getName() {
        return SPI_NUM + "_SIM";
    }

    @Override
    public void removeObserver(PeripheralObserver observer) {

    }

    @Override
    public void reset() {

    }

    @Override
    public void update() {
        byte[] bytes;
        if (spiMonitor.changed()) {
            output();
        }
        if (cycleCount > 100000) {
            systemBooted = true;
        }
        try {
            if (sendFlag) {
                injectedFlag = true;
                sendFlag = false;
                int readByte = response.read();
                byte b = (byte) readByte;
                if (readByte == -1) {
                    messageHandler.outputMessage("End of Stream");
                    System.exit(0);
                } else {
                    messageHandler.outputMessage(String.format("Injecting: 0x%02X ", b)); // Returns the next char which will be injected
                    sfrBuff.privilegedWrite(b); // Inject the next char
                    sfrStat.privilegedSetFieldValue("SPIRBF", 1);
                }
            }
        } catch (IOException e) {
            messageHandler.outputMessage("Exception reading character from res " + e);
            return;
        }
        cycleCount++;
    }

    // Debugging function for manually adding a string to chars
    public void setString(String str) {
        byte[] strAsByte = str.getBytes();
        for (int i = 0; i < strAsByte.length; i++) {
            bytes.add(strAsByte[i]);
        }
    }

    // Try to write bytes to the request file
    public void output() {
        if (systemBooted) {
            try {
                if (injectedFlag) {
                    injectedFlag = false;
                } else {
                    lastRead = sfrBuff.read();
                    messageHandler.outputMessage(String.format("Reading from SPI: 0x%02X", lastRead));
                    request.write((byte) lastRead);
                    request.flush();
                    sendFlag = true;
                }
            } catch (Exception e) {
                messageHandler.outputMessage("Failed to write request byte: " + e);
            }
        }
    }

    public boolean openSockets() {
        try {
            reqSocket = new Socket("localhost", 5555);
        } catch (IOException e) {
            messageHandler.outputError(e);
            messageHandler.outputMessage("Failed to open socket. Is there an external listener running?");
            return false;
        } catch (SecurityException e) {
            messageHandler.outputError(e);
            return false;
        } catch (IllegalArgumentException e) {
            messageHandler.outputError(e);
            messageHandler.outputMessage("Provided port is outside of valid values (0-65535).");
            return false;
        } catch (NullPointerException e) {
            messageHandler.outputError(e);
            return false;
        }

        try {
            request = new BufferedOutputStream(reqSocket.getOutputStream());
            response = new BufferedInputStream(reqSocket.getInputStream());
        } catch (IOException e) {
            messageHandler.outputError(e);
            messageHandler.outputMessage("Failed to open Req/Res streams.");
            return false;
        }
        return true;
    }
}
