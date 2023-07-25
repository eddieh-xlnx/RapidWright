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
import com.xilinx.rapidwright.design.DesignTools;
import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SiteInst;
import com.xilinx.rapidwright.design.SitePinInst;
import com.xilinx.rapidwright.design.Unisim;
import com.xilinx.rapidwright.device.BEL;
import com.xilinx.rapidwright.device.BELClass;
import com.xilinx.rapidwright.device.BELPin;
import com.xilinx.rapidwright.device.SitePIP;
import com.xilinx.rapidwright.edif.EDIFCell;
import com.xilinx.rapidwright.edif.EDIFHierCellInst;
import com.xilinx.rapidwright.edif.EDIFHierNet;
import com.xilinx.rapidwright.edif.EDIFHierPortInst;
import com.xilinx.rapidwright.edif.EDIFNet;
import com.xilinx.rapidwright.edif.EDIFNetlist;
import com.xilinx.rapidwright.edif.EDIFPort;
import com.xilinx.rapidwright.edif.EDIFPortInst;
import com.xilinx.rapidwright.edif.EDIFTools;
import com.xilinx.rapidwright.rwroute.RouterHelper;
import com.xilinx.rapidwright.tests.CodePerfTracker;
import com.xilinx.rapidwright.util.Pair;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ECOTools {
    public static void disconnectNet(Design design, List<String> pinlist, Map<Net, Set<SitePinInst>> deferredRemovals) {
        final EDIFNetlist netlist = design.getNetlist();
        final Map<EDIFNet, List<EDIFPortInst>> netPortInsts = new HashMap<>();
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
                            leafPortInsts = Collections.singletonList(ehpi);
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

    public static void connectNet(Design design, List<String> net_object_list, Map<Net, Set<SitePinInst>> deferredRemovals, CodePerfTracker t) {
        final EDIFNetlist netlist = design.getNetlist();
        final Map<EDIFHierNet, List<EDIFHierPortInst>> netPortInsts = new HashMap<>(net_object_list.size());
        for (String i : net_object_list) {
            String[] net_pins = i.split(" ", 2);
            String net = net_pins[0];
            if (net.isEmpty()) {
                System.err.println("WARNING: Empty net specified for connection to pins: " + net_pins[1]);
                continue;
            }
            EDIFHierNet ehn = netlist.getHierNetFromName(net);
            if (ehn == null) throw new RuntimeException(net);

            String[] pins = net_pins[1].split("[{} ]+");
            Collection<EDIFHierPortInst> portInsts = netPortInsts.computeIfAbsent(ehn, (n) -> new ArrayList<>(pins.length));
            for (String pin : pins) {
                if (pin.isEmpty())
                    continue;
                EDIFHierPortInst ehpi = netlist.getHierPortInstFromName(pin);
                if (ehpi == null) {
                    // No HierPortInst exists -- attach one to the port and net,
                    // creating a net if necessary
                    int pos = pin.lastIndexOf(EDIFTools.EDIF_HIER_SEP);
                    String path = pin.substring(0, pos);
                    String name = pin.substring(pos+1);
                    EDIFHierCellInst ehci = netlist.getHierCellInstFromName(path);
                    if (ehci == null) throw new RuntimeException(pin);
                    EDIFCell cell = ehci.getCellType();
                    EDIFPort ep = cell.getPort(name);
                    if (ep == null) throw new RuntimeException(pin);
                    // Create and attach port inst here because port insts need (CHECK!!)
                    // to be attached to a net, so don't do it below
                    // EDIFPortInst epi = en.createPortInst(ep, ehci.getInst());
                    EDIFPortInst epi = new EDIFPortInst(ep, null, ehci.getInst());
                    ehpi = new EDIFHierPortInst(ehci.getParent(), epi);
                }
                portInsts.add(ehpi);
            }
        }

        for (Map.Entry<EDIFHierNet,List<EDIFHierPortInst>> e : netPortInsts.entrySet()) {
            EDIFHierNet ehn = e.getKey();
            EDIFNet en = ehn.getNet();

            // Sort portInsts so outputs are first
            List<EDIFHierPortInst> portInsts = e.getValue();
            portInsts.sort((p1, p2) -> -Boolean.compare(p1.isOutput(), p2.isOutput()));

            for (EDIFHierPortInst ehpi : portInsts) {
                if (ehpi.isOutput()) {
                    for (EDIFHierPortInst src : ehn.getLeafHierPortInsts(true, false)) {
                        System.err.println("WARNING: Net '" + ehn.getHierarchicalNetName() + "' already has an output pin '" +
                                src + "'. Replacing with new pin '" + ehpi + "'.");
                        Cell cell = src.getPhysicalCell(design);
                        for (SitePinInst spi : cell.getAllSitePinsFromLogicalPin(src.getPortInst().getName(), null)) {
                            deferredRemovals.computeIfAbsent(spi.getNet(), (p) -> new HashSet<>()).add(spi);
                        }
                        src.getNet().removePortInst(src.getPortInst());
                    }
                }
                if (ehn.getHierarchicalInst().equals(ehpi.getHierarchicalInst())) {
                    // Attach if port inst not attached by above
                    EDIFPortInst epi = ehpi.getPortInst();
                    if (!en.getPortInsts().contains(epi)) {
                        en.addPortInst(epi);
                    }
                } else {
                    // Use a unique baseName derived from the net name, to avoid
                    // name collisions with bus nets -- e.g. when baseName is 'foo'
                    // it may be possible that some cell in the hierarchy may contain
                    // a bus net 'foo' -- represented only by a collection of
                    // possible-arbitrarily-indexed single-bit nets: 'foo[i]', 'foo[j]',
                    // 'foo[k]', etc. making it impractical to identify if a bus net exists.
                    String baseName = ehn.getNet().getName() + EDIFTools.getUniqueSuffix();
                    EDIFTools.connectPortInstsThruHier(ehn, ehpi, baseName);
                }
            }
        }

        t.stop().start("generateParentNetMap");
        netlist.resetParentNetMap();
        netlist.getParentNetMap();
        t.stop().start("connect_net (cont'd)");

        EDIFCell ecGnd = netlist.getHDIPrimitive(Unisim.GND);
        EDIFCell ecVcc = netlist.getHDIPrimitive(Unisim.VCC);

        for (EDIFHierNet ehn : netPortInsts.keySet()) {
            Net newPhysNet = null;

            List<EDIFHierPortInst> leafPins = ehn.getLeafHierPortInsts(true);
            EDIFHierPortInst sourceEhpi = null;
            SiteInst sourceSi = null;
            BELPin sourceBELPin = null;
            for (EDIFHierPortInst ehpi : leafPins) {
                if (ehpi.isOutput()) {
                    if (sourceEhpi != null) throw new RuntimeException(ehn.getHierarchicalNetName());
                    sourceEhpi = ehpi;
                    Cell sourceCell = sourceEhpi.getPhysicalCell(design);
                    if (sourceCell == null) {
                        EDIFCell eci = ehpi.getCellType();
                        if (eci.equals(ecGnd)) {
                            newPhysNet = design.getGndNet();
                        } else if (eci.equals(ecVcc)) {
                            newPhysNet = design.getVccNet();
                        } else {
                            throw new RuntimeException(sourceEhpi.toString());
                        }
                    } else {
                        sourceSi = sourceCell.getSiteInst();
                        sourceBELPin = sourceCell.getBELPin(sourceEhpi);
                    }
                }
            }

            if (newPhysNet == null) {
                EDIFHierNet parentEhn = netlist.getParentNet(ehn);
                if (parentEhn != null) {
                    newPhysNet = design.getNet(parentEhn.getHierarchicalNetName());
                } else {
                    // This can occur for newly created nets
                }
                if (newPhysNet == null) {
                    // Try the logical alias' physical net, if it exists
                    newPhysNet = design.getNet(ehn.getHierarchicalNetName());
                }
                if (newPhysNet == null) {
                    // Lastly, create one (since even the logical alias could have been changed)
                    newPhysNet = design.createNet(parentEhn != null ? parentEhn : ehn);
                }
            } else if (newPhysNet.isStaticNet()) {
                Net oldPhysNet = design.getNet(ehn.getHierarchicalNetName());
                if (oldPhysNet != null) {
                    // Propagate the net type so that it can be reconciled by
                    // route_design's makePhysNetNamesConsistent
                    oldPhysNet.setType(newPhysNet.getType());

                    Net usedNet = design.getNet(Net.USED_NET);
                    if (usedNet == null) {
                        usedNet = design.createNet(Net.USED_NET);
                    }
                    // Unroute and remove all output pins on this net
                    oldPhysNet.unroute();
                    for (SitePinInst spi : Arrays.asList(oldPhysNet.getAlternateSource(), oldPhysNet.getSource())) {
                        if (spi == null) {
                            continue;
                        }

                        BELPin spiBELPin = spi.getBELPin();
                        for (EDIFHierPortInst ehpi : DesignTools.getPortInstsFromSitePinInst(spi)) {
                            Pair<SiteInst, BELPin> p = ehpi.getRoutedBELPin(design);
                            p.getFirst().unrouteIntraSiteNet(p.getSecond(), spiBELPin);
                        }
                        spi.getSiteInst().removePin(spi);
                        oldPhysNet.removePin(spi);

                        // Mark the output pin sitewire with USED_NET to block it from being
                        // used as a static source
                        spi.getSiteInst().routeIntraSiteNet(usedNet, spiBELPin, spiBELPin);
                    }
                }
            }

            // Walk through all leaf pins of the target net, replacing existing source pins
            // on the physical net, and moving sink pins from its previous physical net to
            // the new net

            // Extract all site pins upfront, since changing pins may alter its result
            // e.g. when a LUT input is used by the LUT6 and as a routethru on LUT5
            List<List<SitePinInst>> leafSitePins = new ArrayList<>(leafPins.size());
            for (EDIFHierPortInst ehpi : leafPins) {
                List<SitePinInst> sitePins;
                Cell cell = ehpi.getPhysicalCell(design);
                if (cell == null) {
                    EDIFCell eci = ehpi.getCellType();
                    if (eci.equals(ecGnd) || eci.equals(ecVcc)) {
                        sitePins = null;
                    } else {
                        throw new RuntimeException(ehpi.toString());
                    }
                } else {
                    sitePins = cell.getAllSitePinsFromLogicalPin(ehpi.getPortInst().getName(), null);
                }
                leafSitePins.add(sitePins);
            }

            // Now perform modifications
            Iterator<List<SitePinInst>> it = leafSitePins.iterator();
            for (EDIFHierPortInst ehpi : leafPins) {
                List<SitePinInst> sitePins = it.next();
                if (sitePins == null) {
                    continue;
                }

                Cell cell = ehpi.getPhysicalCell(design);
                if (cell == null) {
                    throw new RuntimeException(ehpi.toString());
                }
                SiteInst si = cell.getSiteInst();

                if (ehpi.isOutput()) {
                    if (!sitePins.isEmpty()) {
                        for (SitePinInst spi : sitePins) {
                            Net oldPhysNet = spi.getNet();
                            if (oldPhysNet != null && !oldPhysNet.equals(newPhysNet)) {
                                deferredRemovals.computeIfPresent(oldPhysNet, ($, v) -> {
                                    v.remove(spi);
                                    return v.isEmpty() ? null : v;
                                });
                                fullyUnrouteSources(oldPhysNet);
                            }
                            if (oldPhysNet == null || !oldPhysNet.equals(newPhysNet)) {
                                deferredRemovals.computeIfPresent(newPhysNet, (k, v) -> {
                                    v.remove(k.getSource());
                                    return v.isEmpty() ? null : v;
                                });
                                fullyUnrouteSources(newPhysNet);
                                Pair<SiteInst, BELPin> siteInstBelPin = ehpi.getRoutedBELPin(design);
                                assert(siteInstBelPin.getFirst() == si);
                                assert(spi.getSiteInst() == null);
                                spi.setSiteInst(si);
                                newPhysNet.addPin(spi);
                                si.routeIntraSiteNet(newPhysNet, siteInstBelPin.getSecond(), spi.getBELPin());
                            }
                        }
                    } else {
                        deferredRemovals.computeIfPresent(newPhysNet, (k, v) -> {
                            v.remove(k.getSource());
                            return v.isEmpty() ? null : v;
                        });
                        fullyUnrouteSources(newPhysNet);
                        // Need intra-site routing to get out of site
                        createExitSitePinInst(design, ehpi, newPhysNet);
                    }
                } else {
                    if (!sitePins.isEmpty()) {
                        String logicalPinName = ehpi.getPortInst().getName();
                        BEL bel = cell.getBEL();
                        for (String physicalPinName : cell.getAllPhysicalPinMappings(logicalPinName)) {
                            String sitePinName = cell.getCorrespondingSitePinName(logicalPinName, physicalPinName, null);
                            SitePinInst spi = si.getSitePinInst(sitePinName);
                            if (spi == null) {
                                continue;
                            }
                            // Check that all port insts serviced by this SPI are on this net
                            List<EDIFHierPortInst> portInstsOnSpi = DesignTools.getPortInstsFromSitePinInst(spi);
                            assert(portInstsOnSpi.contains(ehpi));
                            EDIFHierNet parentNet = netlist.getParentNet(ehpi.getHierarchicalNet());
                            for (EDIFHierPortInst otherEhpi : portInstsOnSpi) {
                                if (otherEhpi.equals(ehpi)) {
                                    continue;
                                }
                                EDIFHierNet otherParentNet = netlist.getParentNet(otherEhpi.getHierarchicalNet());
                                if (!otherParentNet.equals(parentNet)) {
                                    System.err.println("WARNING: Site pin " + spi.getSitePinName() + " cannot be used " +
                                            "to connect to logical pin '" + ehpi + "' since it is also connected to pin '" +
                                            otherEhpi + "'.");
                                }
                            }

                            Net oldPhysNet = spi.getNet();
                            deferredRemovals.computeIfPresent(oldPhysNet, ($, v) -> {
                                v.remove(spi);
                                return v.isEmpty() ? null : v;
                            });
                            if (!oldPhysNet.equals(newPhysNet)) {
                                // Unroute and remove pin from old net
                                BELPin snkBp = bel.getPin(physicalPinName);
                                if (!si.unrouteIntraSiteNet(spi.getBELPin(), snkBp)) {
                                    throw new RuntimeException("Failed to unroute intra-site connection " +
                                            spi.getSiteInst().getSiteName() + "/" + spi.getBELPin() + " to " + snkBp);
                                }
                                boolean preserveOtherRoutes = true;
                                oldPhysNet.removePin(spi, preserveOtherRoutes);
                                if (RouterHelper.isLoadLessNet(oldPhysNet) && oldPhysNet.hasPIPs()) {
                                    // Since oldPhysNet has no sink pins left, yet still has PIPs, then it may
                                    // mean that a routing stub persevered. To handle such cases, unroute the
                                    // whole net.
                                    oldPhysNet.unroute();
                                }

                                // Re-do intra-site routing and add pin to new net
                                if (!si.routeIntraSiteNet(newPhysNet, spi.getBELPin(), snkBp)) {
                                    throw new RuntimeException("Failed to route intra-site connection " +
                                            spi.getSiteInst().getSiteName() + "/" + spi.getBELPin() + " to " + snkBp);
                                }
                                newPhysNet.addPin(spi);
                                spi.setRouted(false);
                            }
                        }
                    } else {
                        // If source and sink pin happen to be in the same site, try intra-site routing
                        if (!si.equals(sourceSi) || !si.routeIntraSiteNet(newPhysNet, sourceBELPin, cell.getBELPin(ehpi))) {
                            String logicalPinName = ehpi.getPortInst().getName();
                            if (cell.getAllPhysicalPinMappings(logicalPinName) != null) {
                                // If that's not relevant or fails, then create a site pin so inter-site routing will occur
                                SitePinInst spi = createExitSitePinInst(design, ehpi, newPhysNet);
                                if (spi == null) {
                                    throw new RuntimeException(ehpi.toString());
                                }

                                BELPin snkBp = cell.getBELPin(ehpi);
                                if (!si.routeIntraSiteNet(newPhysNet, spi.getBELPin(), snkBp)) {
                                    throw new RuntimeException("Failed to route intra-site connection " +
                                            spi.getSiteInst().getSiteName() + "/" + spi.getBELPin() + " to " + snkBp);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Creates a new SitePinInst source for the physical net provided.  It will route out an
     * internal pin to a site pin output.
     * @param design The current design
     * @param targetHierSrc The hierarchical source pin
     * @param newPhysNet The net to add the new source pin to
     * @return The newly created source site pin
     */
    private static SitePinInst routeOutSitePinInstSource(Design design,
                                                         EDIFHierPortInst targetHierSrc, Net newPhysNet) {
        // This net is internal to a site, need to create a site pin and route out to it
        Cell srcCell = design.getCell(targetHierSrc.getFullHierarchicalInstName());
        SiteInst si = srcCell.getSiteInst();
        String sitePinName = null;
        // Find the first available site pin (e.g. between ?_O and ?MUX)
        List<String> sitePins = srcCell.getAllCorrespondingSitePinNames(targetHierSrc.getPortInst().getName());
        for (String pin : sitePins) {
            if (si.getSitePinInst(pin) == null) {
                sitePinName = pin;
                break;
            }
        }
        if (sitePinName == null) {
            BELPin output = srcCell.getBELPin(targetHierSrc);
            // Unroute single output path of slice
            // Identify case (O5 blocked, reroute O6 to _O pin, route O5 out MUX output)
            if (output.getName().equals("O5") && sitePins.size()==1 && sitePins.get(0).endsWith("MUX")) {
                char lutID = output.getBELName().charAt(0);

                // Remove OUTMUX SitePIP from O6
                String rBELName = "OUTMUX" + lutID;
                SitePIP sitePIP = si.getUsedSitePIP(rBELName);
                // TODO: Use DesignTools.unrouteAlternativeOutputSitePin() instead
                si.unrouteIntraSiteNet(sitePIP.getInputPin(), sitePIP.getOutputPin());

                // Move source from *MUX -> *_O
                sitePinName = lutID + "MUX";
                SitePinInst lut6MuxOutput = si.getSitePinInst(sitePinName);
                Net lut6Net = lut6MuxOutput.getNet();
                lut6Net.removePin(lut6MuxOutput, true);
                si.removePin(lut6MuxOutput);
                String mainPinName = lutID + "_O";
                SitePinInst lut6MainOutput = si.getSitePinInst(mainPinName);
                if (lut6MainOutput != null) {
                    // lut6Net already using the _O output
                    assert(lut6MainOutput.getNet().equals(lut6Net));

                    // Since unrouteIntraSiteNet() above removed the nets on the ?_O (sitePIP
                    // input) and ?MUX (sitePIP output) sitewires, restore the former here
                    si.routeIntraSiteNet(lut6Net, sitePIP.getInputPin(), sitePIP.getInputPin());
                } else {
                    lut6Net.createPin(mainPinName, si);
                }

                // Reconfigure OUTMUX SitePIP to use O5
                sitePIP = si.getSitePIP(rBELName, "D5");
                si.routeIntraSiteNet(newPhysNet, sitePIP.getInputPin(), sitePIP.getOutputPin());
            }else {
                System.err.println("ERROR: Unable to exit site for target src: "
                        + targetHierSrc + " " + output + " -> " + sitePins);
                return null;
            }
        }
        SitePinInst srcPin = newPhysNet.createPin(sitePinName, si);
        BELPin belPinSrc = srcCell.getBELPin(targetHierSrc);
        if (!si.routeIntraSiteNet(newPhysNet, belPinSrc, srcPin.getBELPin())) {
            System.err.println("ERROR: Failed to route to site pin from target src: "
                    + targetHierSrc);
        }
        return srcPin;
    }

    public static SitePinInst createExitSitePinInst(Design design, EDIFHierPortInst ehpi, Net net) {
        if (ehpi.isOutput()) {
            return routeOutSitePinInstSource(design, ehpi, net);
        }

        Cell cell = ehpi.getPhysicalCell(design);
        SiteInst si = cell.getSiteInst();
        String logicalPinName = ehpi.getPortInst().getName();
        List<String> siteWires = new ArrayList<>();
        final boolean considerLutRoutethru = true;
        List<String> sitePinNames = cell.getAllCorrespondingSitePinNames(logicalPinName, siteWires, considerLutRoutethru);
        if (sitePinNames.isEmpty()) {
            // Following existing intra-site routing did not get us to a site pin
            // (e.g. previous driver of this BELPin could be a LUT)
            assert(!siteWires.isEmpty());

            // Unroute the first SitePIP
            for (String sitewire : siteWires) {
                BELPin[] belPins = si.getSiteWirePins(sitewire);
                BELPin srcBp = belPins[0].getSourcePin();
                BEL srcBEL = srcBp.getBEL();
                if (srcBEL.getBELClass() == BELClass.RBEL) {
                    SitePIP spip = si.getUsedSitePIP(srcBp);
                    if (spip == null) {
                        continue;
                    }

                    BELPin inputBp = spip.getInputPin();

                    // Save the net on inputBp's sitewire, since
                    // SiteInst.unrouteIntraSiteNet() will rip it up
                    Net inputBpNet = si.getNetFromSiteWire(inputBp.getSiteWireName());

                    // Unroute SitePIP
                    BELPin cellBp = cell.getBELPin(ehpi);
                    if (!si.unrouteIntraSiteNet(inputBp, cellBp)) {
                        throw new RuntimeException("Failed to unroute intra-site connection " +
                                si.getSiteName() + "/" + inputBp + " to " + cellBp);
                    }

                    // Restore inputBp's sitewire
                    if (inputBpNet != null) {
                        si.routeIntraSiteNet(inputBpNet, inputBp, inputBp);
                    }

                    // Try again
                    sitePinNames = cell.getAllCorrespondingSitePinNames(logicalPinName, siteWires, considerLutRoutethru);
                    break;
                }
            }
        }

        for (String sitePinName : sitePinNames) {
            Net siteWireNet = si.getNetFromSiteWire(sitePinName);
            if (siteWireNet == null) {
                // Site Pin not currently used
                return net.createPin(sitePinName, si);
            }
        }

        throw new RuntimeException(ehpi.toString());
    }

    // Unroute both primary and alternate site pin sources on a net, should they exist,
    // and remove those pins from their SiteInst too.
    // Also rip up associated intra-site routing.
    private static void fullyUnrouteSources(Net net) {
        for (SitePinInst spi : Arrays.asList(net.getSource(), net.getAlternateSource())) {
            if (spi == null) continue;
            BELPin srcBp = DesignTools.getLogicalBELPinDriver(spi);
            SiteInst si = spi.getSiteInst();
            si.unrouteIntraSiteNet(srcBp, spi.getBELPin());
            net.removePin(spi);
            si.removePin(spi);
            spi.setSiteInst(null);
        }
    }
}
