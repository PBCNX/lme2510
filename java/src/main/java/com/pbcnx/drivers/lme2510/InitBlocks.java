/*
 * Copyright (c) 2026 The LME2510 Open Driver Authors.  MIT.
 *  Marker: lme2510-pbcnx.
 */
package com.pbcnx.drivers.lme2510;

import java.util.ArrayList;
import java.util.List;

/**
 * The warm init's literal blocks, verbatim from the capture (see
 * 3, "the 330-frame init"). They carry no decisions:
 * every byte is a constant. The parts that decide are {@link InitScript},
 * {@link Calibration} and {@link Sweep}.
 */
public final class InitBlocks {

    public static final String PREFIX =
            "0403be0026 8602bf0226 0404b0001380 0404be00c503 0404be00c500 0404be005001 "
            + "0404be005100 0404be008500 0404be005201 0405be0055121d 0404be005200 0404be008501 "
            + "0403be0055 8602bf0255 0404b0001000 0404b0006001 0404b0006100 0405b00065221c "
            + "0404b0006200 0404be00017f 0404be0001cf 0404b00001ff 0404be00b400 0404be00b500 "
            + "0404be00b306 0404be00ba01 0404be00b900 0404be00ae00 0407be00e077777707 0404be102071 "
            + "0404be10690a 0404be100027 0404be108316 0404be108416 0404be305206 0405be31040019 "
            + "0405be1008e003 0405be40703f00 0405be42002006 0404be101000 0404be250220 0404be254810 "
            + "0404be253510 0404be255104 0404be255220 0404be41e53e 0405be41c68000 0404be417f0c "
            + "0404be303200 0404be257c01 0404be417e40 0404be209738 0404be251801 0404be250532 "
            + "0404be250e3f 0404be25323f 0404be255001 0404be2514ff 0404be251eff 0404b0420802 "
            + "0404b0430716 0405be80100000 0405be8020ffea 0405be80307f75 0405be801800eb 0405be8028cbeb "
            + "0405be80386500 0405be801acceb 0405be802ac3f3 0405be803afb03 0405be801cc4f3 0405be802c5bf5 "
            + "0405be803ccb00 0405be801e5cf5 0405be802e77f5 0405be803e0d00 0404b000118e 0404be002000 "
            + "0404be00121f 0404bea00000 0404b0001701 0404b0800201 0404b0800301 0404b0000301 "
            + "0404b0000601 ";

    public static final String AFTER_SCRIPT =
            "0404be0f4906 0404be0f4a04 0404be0f4b1a 0404be0ffe08 0403b00f43 8602b10143 "
            + "0404be0fee02 0404be0ff152 0404be0ff230 0404be0ff3f2 0404be0ff430 0404be0f7c30 "
            + "0404be0f7eff 0404be0f7fff ";

    public static final String DEFAULT_TUNE =
            "0404be0fe96f 0404be0f5270 0404be0f1f79 0404b00f7531 0404b00f7700 0404b00f5101 "
            + "0404b00f570b 0404b00f5aa7 0404b00f50e3 0404b00f50ff 0404b00f58c7 0404b00f59eb ";

    public static final String AFTER_DEFAULT_TUNE =
            "0403b00f64 8602b10164 0403b00f65 8602b10165 0403b00f76 8602b10176 "
            + "0404be0f7531 0404be0f78f1 0404be0f0412 0404be0f7831 0404be0f0410 0404be0f1e10 "
            + "0404be0f1ff9 0404be0f7837 0404be0f0412 0404be0f8d08 0404be0f8e13 0404be0f1307 "
            + "0404be0f2201 0404be0f2411 0404be0f2317 ";

    public static final String AFTER_SWEEP =
            "0404be0f1e00 0404be0f1f79 0404be0f7831 0404be0f0410 0404be0f2200 0404be008500 "
            + "0404be005201 0405be0055121d 0404be005200 0404be008501 0403be0055 8602bf0255 "
            + "0405be10320000 0407be2008cdf70000 0407be103c00808710 0407be1040fd000404 0407be1044fef9fb04 0407be10480b05f6ed "
            + "0407be104cfa20495b 0404be001607 0404be301000 0407be41f101010c00 0407be41f501030001 0407be41f902000302 "
            + "0404be41fd00 0405be50191bc2 0404be501b03 0404be500202 0404be202118 0405be4247c300 "
            + "0404be310001 0404be10640c ";

    private InitBlocks() {
    }

    public static List<byte[]> frames(String block) {
        List<byte[]> out = new ArrayList<>();
        for (String hex : block.trim().split("\\s+")) {
            int length = hex.length() / 2;
            byte[] frame = new byte[length];
            for (int index = 0; index < length; index++) {
                frame[index] = (byte) Integer.parseInt(
                        hex.substring(index * 2, index * 2 + 2), 16);
            }
            out.add(frame);
        }
        return out;
    }
}

