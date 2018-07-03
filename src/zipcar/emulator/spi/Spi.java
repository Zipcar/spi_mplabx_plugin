package zipcar.emulator.spi;

import com.microchip.mplab.mdbcore.simulator.Peripheral;
import com.microchip.mplab.mdbcore.simulator.SFR;
import com.microchip.mplab.mdbcore.simulator.SFRSet;
import com.microchip.mplab.mdbcore.simulator.scl.SCL;
import com.microchip.mplab.mdbcore.simulator.MessageHandler;
import com.microchip.mplab.mdbcore.simulator.SimulatorDataStore.SimulatorDataStore;
import com.microchip.mplab.mdbcore.simulator.PeripheralSet;
import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.util.LinkedList;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import org.openide.util.lookup.ServiceProvider;
import java.util.Map;
import org.yaml.snakeyaml.Yaml;

@ServiceProvider(path = Peripheral.REGISTRATION_PATH, service = Peripheral.class)
public class Spi implements Peripheral {

    String SPI_NUM; // SPI Name (eg: SPI1, SPI2, etc...)
    String SPI_BUFF; // Respective SPI BUFFER SFR
    String SPI_STAT; // SPI STAT buffer
    String REQUEST_FILE; // Request File Path (eg: "~/uartfolder/req"
    String RESPONSE_FILE; // Response File Path (eg: "~/uartfolder/res"

    static Spi instance;
    SimulatorDataStore DS;
    MessageHandler messageHandler;
    SCL scl;

    SFR sfrBuff;
    SFR sfrStat;
    SFR sfrTX;
    SFRSet sfrs;

    LinkedList<Byte> bytes = new LinkedList<Byte>();
    BufferedWriter request;
    BufferedInputStream response;
    Yaml yaml = new Yaml();

    int updateCounter = 0;
    int cycleCount = 0;
    boolean notInitialized = true;
    long lastRead;
    long lastSent;
    boolean sendFlag = false;
    String tempStr = "";
    
    @Override
    public boolean init(SimulatorDataStore DS) {
        // Initialize DS
        this.DS = DS;

        // Initialize messageHandler
        messageHandler = DS.getMessageHandler();

        // Initialize instance variables
        try {
            FileInputStream conf = new FileInputStream(new File("spiconfig.yml"));
            Map config = (Map) yaml.load(conf);
            SPI_NUM = config.get("spiNum").toString();
            SPI_BUFF = config.get("spiBuff").toString();
            SPI_STAT = config.get("spiStat").toString();
            REQUEST_FILE = config.get("requestFile").toString();
            RESPONSE_FILE = config.get("responseFile").toString();
        } catch (Exception e) {
            messageHandler.outputError(e);
            // return false;
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

        // Setup pipes
        /* try {
            request = new FileOutputStream(REQUEST_FILE);
            response = new FileInputStream(RESPONSE_FILE);
        } catch (FileNotFoundException e) {
            messageHandler.outputMessage("Exception in init: " + e);
            return false;
        } */
        
        // Setup Sockets
        try {
            Socket reqSocket = new Socket("localhost", 5555);
            request = new BufferedWriter(new OutputStreamWriter(reqSocket.getOutputStream()));
            response = new BufferedInputStream(reqSocket.getInputStream());
        } catch (Exception e) {
            messageHandler.outputError(e);
        } 

        // Add observers
        SpiObserver obs = new SpiObserver();
        sfrBuff.addObserver(obs);

        messageHandler.outputMessage("External Peripheral Initialized: SPI");
        instance = this;

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
        try {
            if (sendFlag) {
                long readByte = response.read();
                byte b = (byte) readByte;
                if ((int) b == -1) {
                    messageHandler.outputMessage("End of Stream");
                    System.exit(0);
                } else {
                    messageHandler.outputMessage(String.format("Injecting: 0x%02X ", b)); // Returns the next char which will be injected
                    sfrBuff.privilegedWrite(lastSent); // Inject the next char
                    sendFlag = false;
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
        if (cycleCount > 10000) {
            try {
                lastRead = sfrBuff.read();
                messageHandler.outputMessage(String.format("Reading from SPI: 0x%02X", lastRead));
                request.write((byte) lastRead);
                request.flush();
                sendFlag = true;
                sfrStat.privilegedSetFieldValue("SPIRBF", 1);
            } catch (Exception e) {
                messageHandler.outputMessage("Failed to write request byte: " + e);
            }
        }
    }

    public static Spi get() {
        return instance;
    }
}
