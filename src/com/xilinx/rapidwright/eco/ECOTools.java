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

import com.xilinx.rapidwright.design.Cell;
import com.xilinx.rapidwright.design.Design;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFTools;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ECOTools {
    public static void disconnectNet(Design design, List<String> pinlist, Map<Net, Set<SitePinInst>> deferredRemovals) {
        EDIFNetlist netlist = design.getNetlist();
        Map<EDIFNet, List<EDIFPortInst>> netPortInsts = new HashMap<>();
        for (String pins : pinlist) {
            for (String pin : pins.split(" ")) {
                EDIFHierPortInst ehpi = netlist.getHierPortInstFromName(pin);
                if (ehpi == null) {
                    int pos = pin.lastIndexOf(EDIFTools.EDIF_HIER_SEP);
                    String path = pin.substring(0, pos);
                    EDIFHierCellInst ehci = netlist.getHierCellInstFromName(path);
                    if (ehci == null) {
                        throw new RuntimeException("Unable to find inst '" + path + "' corresponding to pin '" + pin + "'");
                    }
                    String name = pin.substring(pos + 1);
                    EDIFPort ep = ehci.getCellType().getPort(name);
                    if (ep == null) {
                        throw new RuntimeException("Unable to find port '" + name + "' on inst '" + path + "' corresponding to pin '" + pin + "'");
                    }

                    // Cell inst exists, as does the port on its cell type, but port inst
                    // does not because it is not connected to anything. Nothing to be done.
                    continue;
                }
                EDIFHierNet ehn = ehpi.getHierarchicalNet();
                if (ehn == null) throw new RuntimeException(pin);

                List<EDIFHierPortInst> leafPortInsts;
                EDIFHierNet internalEhn = ehpi.getInternalNet();
                if (internalEhn == null) {
                    // Pin does not have an internal net (the net on the other side
                    // of the port, inside the cell)
                    EDIFCell ec = ehpi.getCellType();
                    if (ec.isLeafCellOrBlackBox()) {
                        // Pin is a leaf cell
                        if (ehpi.isInput()) {
                            // Input leaf port, meaning that this is the only pin affected
                            leafPortInsts = Arrays.asList(ehpi);
                        } else {
                            // Output leaf port, thus all sinks are affected
                            assert (ehpi.isOutput());
                            leafPortInsts = ehn.getLeafHierPortInsts(true);
                        }
                    } else {
                        // Pin must be unconnected (thus cannot have any leaf ports)
                        leafPortInsts = Collections.emptyList();
                    }
                } else {
                    // Not a leaf pin

                    // Check downstream
                    Set<EDIFHierNet> visitedNets = new HashSet<>();
                    visitedNets.add(ehn);

                    leafPortInsts = internalEhn.getLeafHierPortInsts(true, visitedNets);

                    if (ehpi.isInput()) {
                        // Pin is an input, cannot contain a source, remove all downstream sinks
                    } else {
                        // Pin is an output or inout, check if downstream contains a source
                        boolean sourcePresent = false;
                        for (EDIFHierPortInst leafPortInst : leafPortInsts) {
                            if (leafPortInst.isOutput()) {
                                sourcePresent = true;
                                break;
                            }
                        }

                        if (sourcePresent) {
                            // Downstream does contains a source, remove everything upstream instead
                            visitedNets.clear();
                            visitedNets.add(internalEhn);
                            leafPortInsts = ehn.getLeafHierPortInsts(false, visitedNets);
                        } else {
                            // Downstream contains only sinks, remove them all
                        }
                    }
                }

                // Remove all affected SitePinInsts
                for (EDIFHierPortInst leafEhpi : leafPortInsts) {
                    // Do nothing if we're disconnecting an input, but this leaf pin is not an input
                    if (ehpi.isInput() && !leafEhpi.isInput())
                        continue;

                    Cell cell = leafEhpi.getPhysicalCell(design);
                    for (SitePinInst spi : cell.getAllSitePinsFromLogicalPin(leafEhpi.getPortInst().getName(), null)) {
                        deferredRemovals.computeIfAbsent(spi.getNet(), (p) -> new HashSet<>()).add(spi);
                    }
                }

                EDIFNet en = ehn.getNet();
                netPortInsts.computeIfAbsent(en, (n) -> new ArrayList<>()).add(ehpi.getPortInst());
            }
        }

        for (Map.Entry<EDIFNet, List<EDIFPortInst>> e : netPortInsts.entrySet()) {
            EDIFNet en = e.getKey();
            for (EDIFPortInst epi : e.getValue()) {
                // Detach from net, but do not detach from cell instance since
                // typically we would want to connect it to another net
                en.removePortInst(epi);
            }
        }
    }
}
