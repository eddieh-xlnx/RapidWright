/*
 * Copyright (c) 2023, Advanced Micro Devices, Inc.
 * All rights reserved.
 *
 * Author: Eddie Hung, Advanced Micro Devices, Inc.
 *
 * This file is part of RapidWright.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.xilinx.rapidwright.eco;

import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.support.RapidWrightDCP;
import com.xilinx.rapidwright.util.FileTools;
import com.xilinx.rapidwright.util.ReportRouteStatusResult;
import com.xilinx.rapidwright.util.VivadoTools;
import org.apache.commons.collections4.CollectionUtils;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class TestECOTools {
    @Test
    public void testDisconnectNet() {
        Design design = RapidWrightDCP.loadDCP("picoblaze_ooc_X10Y235.dcp");
        EDIFNetlist netlist = design.getNetlist();
        Map<Net, Set<SitePinInst>> deferredRemovals = new HashMap<>();

        // *** Internally routed net (input pin)
        {
            EDIFHierPortInst ehpi = netlist.getHierPortInstFromName("processor/parity_muxcy_CARRY4_CARRY8/S[1]");
            EDIFPortInst epi = ehpi.getPortInst();
            EDIFNet en = ehpi.getHierarchicalNet().getNet();
            int portInstsBefore = en.getPortInsts().size();
            Assertions.assertTrue(en.getPortInsts().contains(epi));

            ECOTools.disconnectNet(design, Collections.singletonList(ehpi), deferredRemovals);
            Assertions.assertFalse(en.getPortInsts().contains(epi));
            Assertions.assertEquals(portInstsBefore - 1, en.getPortInsts().size());

            Assertions.assertEquals(0, deferredRemovals.size());
        }
        deferredRemovals.clear();


        // *** Internally routed net (output pin)
        {
            EDIFHierPortInst ehpi = netlist.getHierPortInstFromName("processor/read_strobe_lut/LUT6/O");
            EDIFNet en = ehpi.getHierarchicalNet().getNet();
            int portInstsBefore = en.getPortInsts().size();
            Assertions.assertTrue(en.getPortInsts().contains(ehpi.getPortInst()));

            ECOTools.disconnectNet(design, Collections.singletonList(ehpi), deferredRemovals);
            Assertions.assertFalse(en.getPortInsts().contains(ehpi.getPortInst()));
            Assertions.assertEquals(portInstsBefore - 1, en.getPortInsts().size());

            Assertions.assertEquals(0, deferredRemovals.size());
        }
        deferredRemovals.clear();

        // *** Externally routed 2-pin net (input pin)
        {
            EDIFHierPortInst ehpi = netlist.getHierPortInstFromName("processor/t_state1_flop/D");
            Net net = design.getNet(netlist.getParentNetName(ehpi.getHierarchicalNetName()));
            EDIFNet en = ehpi.getHierarchicalNet().getNet();
            int portInstsBefore = en.getPortInsts().size();
            Assertions.assertTrue(en.getPortInsts().contains(ehpi.getPortInst()));

            ECOTools.disconnectNet(design, Collections.singletonList(ehpi), deferredRemovals);
            Assertions.assertFalse(en.getPortInsts().contains(ehpi.getPortInst()));
            Assertions.assertEquals(portInstsBefore - 1, en.getPortInsts().size());

            Assertions.assertEquals("[IN SLICE_X13Y237.E_I]", deferredRemovals.get(net).toString());
        }
        deferredRemovals.clear();

        // *** Externally routed 2-pin net (output pin)
        {
            EDIFHierPortInst ehpi = netlist.getHierPortInstFromName("your_program/ram_4096x8/DOUTBDOUT[3]");
            Net net = design.getNet(netlist.getParentNetName(ehpi.getHierarchicalNetName()));
            EDIFNet en = ehpi.getHierarchicalNet().getNet();
            int portInstsBefore = en.getPortInsts().size();
            Assertions.assertTrue(en.getPortInsts().contains(ehpi.getPortInst()));

            ECOTools.disconnectNet(design, Collections.singletonList(ehpi), deferredRemovals);
            Assertions.assertFalse(en.getPortInsts().contains(ehpi.getPortInst()));
            Assertions.assertEquals(portInstsBefore - 1, en.getPortInsts().size());

            Assertions.assertEquals("[IN RAMB36_X1Y47.DIBU1, OUT RAMB36_X1Y47.DOBU1]",
                    deferredRemovals.get(net).stream().map(Object::toString).sorted().collect(Collectors.toList()).toString());
        }
        deferredRemovals.clear();

        // *** Externally routed many-pin net (input pin)
        {
            EDIFHierPortInst ehpi = netlist.getHierPortInstFromName("processor/stack_loop[4].upper_stack.stack_pointer_lut/I0");
            Net net = design.getNet(netlist.getParentNetName(ehpi.getHierarchicalNetName()));
            EDIFNet en = ehpi.getHierarchicalNet().getNet();
            int portInstsBefore = en.getPortInsts().size();
            Assertions.assertTrue(en.getPortInsts().contains(ehpi.getPortInst()));

            ECOTools.disconnectNet(design, Collections.singletonList(ehpi), deferredRemovals);
            Assertions.assertFalse(en.getPortInsts().contains(ehpi.getPortInst()));
            Assertions.assertEquals(portInstsBefore - 1, en.getPortInsts().size());

            Assertions.assertEquals("[IN SLICE_X13Y238.E1]", deferredRemovals.get(net).toString());
        }
        deferredRemovals.clear();

        // *** Externally routed many-pin net (output pin)
        {
            EDIFHierPortInst ehpi = netlist.getHierPortInstFromName("processor/alu_mux_sel0_flop/Q");
            Net net = design.getNet(netlist.getParentNetName(ehpi.getHierarchicalNetName()));
            EDIFNet en = ehpi.getHierarchicalNet().getNet();
            int portInstsBefore = en.getPortInsts().size();
            Assertions.assertTrue(en.getPortInsts().contains(ehpi.getPortInst()));

            ECOTools.disconnectNet(design, Collections.singletonList(ehpi), deferredRemovals);
            Assertions.assertFalse(en.getPortInsts().contains(ehpi.getPortInst()));
            Assertions.assertEquals(portInstsBefore - 1, en.getPortInsts().size());

            Assertions.assertEquals("[IN SLICE_X15Y235.G6, IN SLICE_X15Y235.H2, IN SLICE_X15Y237.G5, IN SLICE_X15Y239.H5, IN SLICE_X16Y235.F6, IN SLICE_X16Y235.G4, IN SLICE_X16Y238.D4, IN SLICE_X16Y239.B6, OUT SLICE_X16Y239.EQ]",
                    deferredRemovals.get(net).stream().map(Object::toString).sorted().collect(Collectors.toList()).toString());
        }
        deferredRemovals.clear();

        // *** Externally routed global net (input pin)
        {
            EDIFHierPortInst ehpi = netlist.getHierPortInstFromName("processor/address_loop[10].output_data.pc_vector_mux_lut/I0");
            Net net = design.getGndNet();
            EDIFNet en = ehpi.getHierarchicalNet().getNet();
            int portInstsBefore = en.getPortInsts().size();
            Assertions.assertTrue(en.getPortInsts().contains(ehpi.getPortInst()));

            ECOTools.disconnectNet(design, Collections.singletonList(ehpi), deferredRemovals);
            Assertions.assertFalse(en.getPortInsts().contains(ehpi.getPortInst()));
            Assertions.assertEquals(portInstsBefore - 1, en.getPortInsts().size());

            Assertions.assertEquals("[IN SLICE_X13Y237.G1]", deferredRemovals.get(net).toString());
        }
        deferredRemovals.clear();
    }

    @Test
    public void testConnectNet() {
        Design design = RapidWrightDCP.loadDCP("microblazeAndILA_3pblocks.dcp");
        EDIFNetlist netlist = design.getNetlist();
        Map<Net, Set<SitePinInst>> deferredRemovals = new HashMap<>();

        // Disconnect the ILA inputs
        List<EDIFHierPortInst> disconnectPins = new ArrayList<>();
        for (int i = 0; i < 14; i++) {
            EDIFHierPortInst ehpi = netlist.getHierPortInstFromName("u_ila_0/probe0[" + i + "]");
            Assertions.assertNotNull(ehpi);
            disconnectPins.add(ehpi);
        }
        ECOTools.disconnectNet(design, disconnectPins, deferredRemovals);
        Assertions.assertEquals(14, deferredRemovals.size());

        // Re-connect those inputs to some other nets
        final Map<EDIFHierNet, List<EDIFHierPortInst>> netToPortInsts = new HashMap<>();
        for (int i = 0; i < 14; i++) {
            int busIdx = (74 + i);
            EDIFHierNet ehn = netlist.getHierNetFromName("base_mb_i/microblaze_0/U0/MicroBlaze_Core_I/Performance.Core/Data_Flow_I/Data_Addr[0][" + busIdx + "]");
            EDIFHierPortInst ehpi = disconnectPins.get(i);

            // Check that leaves of net and pin are disjoint
            List<EDIFHierPortInst> ehpiLeaves = ehpi.getInternalNet().getLeafHierPortInsts(false, true);
            Assertions.assertFalse(ehn.getLeafHierPortInsts(false, true).stream().anyMatch(ehpiLeaves::contains));

            netToPortInsts.put(ehn, Collections.singletonList(ehpi));
        }
        ECOTools.connectNet(design, netToPortInsts, deferredRemovals);
        Assertions.assertEquals(0, deferredRemovals.size());

        // Check that leaves of net and pin are one and the same now
        for (Map.Entry<EDIFHierNet, List<EDIFHierPortInst>> e : netToPortInsts.entrySet()) {
            EDIFHierNet ehn = e.getKey();
            List<EDIFHierPortInst> ehnLeaves = ehn.getLeafHierPortInsts(false, true);
            for (EDIFHierPortInst ehpi : e.getValue()) {
                List<EDIFHierPortInst> ehpiLeaves = ehpi.getInternalNet().getLeafHierPortInsts(false, true);
                CollectionUtils.isEqualCollection(ehnLeaves, ehpiLeaves);
            }
        }

        if (FileTools.isVivadoOnPath()) {
            // Check that Vivado shows 14 unrouted nets
            ReportRouteStatusResult rrs = VivadoTools.reportRouteStatus(design);
            Assertions.assertEquals(14, rrs.netsWithRoutingErrors);
        }
    }
}
