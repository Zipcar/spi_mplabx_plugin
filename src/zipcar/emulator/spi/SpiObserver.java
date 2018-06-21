/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package zipcar.emulator.spi;

import com.microchip.mplab.mdbcore.simulator.SFR.SFRObserver;
import com.microchip.mplab.mdbcore.simulator.SFR;

/**
 *
 * @author cgoldader
 */
public class SpiObserver implements SFRObserver {

    @Override
    public void update(SFR generator, SFRObserver.SFREvent event, SFRObserver.SFREventSource source) {

        if (event == SFRObserver.SFREvent.SFR_CHANGED) {
            Spi.get().output();
        }

    }
}
