/*
 *
 * Copyright (c) 2021 Xilinx, Inc.
 * All rights reserved.
 *
 * Author: Pongstorn Maidee, Xilinx Research Labs.
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

package com.xilinx.rapidwright.timing.delayestimator;


import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.xilinx.rapidwright.device.Device;
import com.xilinx.rapidwright.device.IntentCode;
import com.xilinx.rapidwright.device.Node;
import com.xilinx.rapidwright.timing.GroupDelayType;
import com.xilinx.rapidwright.timing.TimingModel;

import static java.lang.Math.max;


/**
 * The base class to implement a delay estimator.
 * Provide basic methods to build a customized estimator.
 */
public class DelayEstimatorBase<T extends InterconnectInfo> implements java.io.Serializable {
    // TODO: Consider moving DelayEstimator as a member of TimingManager.

    // single and double have their own arrays although their values are the same.
    // Using enum as keys to simplify coding. Some types have empty arrays because those types will never be used.
    // distArrays are cumulative and inclusive, ie., for a segment spanning x-y, d[y] is included in d of the segment.
    protected Map<T.Orientation,Map<GroupDelayType, List<Short>>> distArrays;
    protected int numCol;
    protected int numRow;
    
    // These data are sourced from TimingModel.
    // TODO: Consider to move these to TimingModel.
    protected Map<T.Orientation,Map<GroupDelayType, Float>> K0;
    protected Map<T.Orientation,Map<GroupDelayType, Float>> K1;
    protected Map<T.Orientation,Map<GroupDelayType, Float>> K2;
    protected Map<T.Orientation,Map<GroupDelayType, Short>> L;
    protected Map<String, Short>  inputSitePinDelay;

    protected T     ictInfo;
    protected int   verbose;
    protected transient Device device;
    protected boolean useUTurnNodes;


    /**
     * Constructor from a device.
     * Package scope to disable creating DelayEstimatorBase by a user.
     * Create one using DelayEstimatorBuilder instead.
     * @param device target device.
     */
    public DelayEstimatorBase(Device device, T ictInfo, boolean useUTurnNodes, int verbose) {
        this.device  = device;
        this.verbose = verbose;
        this.ictInfo = ictInfo;
        this.useUTurnNodes = useUTurnNodes;
        TimingModel timingModel = new TimingModel(device);
        timingModel.build();
        buildDistanceArrays(timingModel);
        loadInputSitePinDelay(timingModel);
    }


    /**
     * Check if the node is a long node or not
     * @param node  the node to be checked
     * @return      true if the node is a long node
     */
    public static boolean isLong(Node node) {
        return node.getIntentCode() == IntentCode.NODE_VLONG || node.getIntentCode() ==IntentCode.NODE_HLONG;
    }


    /**
     * Return an extra delay if both parent and child are long node.
     * @param child        a child node
     * @param longParent   an indicator if the parent is a long node
     * @return             the extra delay if any
     */
    public static short getExtraDelay(Node child, boolean longParent) {
    	if(!longParent) return 0;
    	
    	IntentCode icChild = child.getIntentCode();
    	if((icChild == IntentCode.NODE_VLONG) || (icChild == IntentCode.NODE_HLONG)) {
    		return 45;
    	}
    	return 0;
    }


    public short getDelayOf(Node exitNode) {
	    TermInfo termInfo = getTermInfo(exitNode);
	    
	    // Don't put this in calcTimingGroupDelay because it is called many times to estimate delay.
	    if (termInfo.ng == T.NodeGroupType.CLE_IN) {
	    	return inputSitePinDelay.getOrDefault(exitNode.getWireName(), (short) 0);
	    }
	    
	    return calcTimingGroupDelay(termInfo.ng, termInfo.begin(), termInfo.end(), 0d);
    }

    /**
     * Represent a routing resource in the delay graph
     */
    protected class TermInfo {
        // INT_TILE coordinate
        short x;
        short y;
        InterconnectInfo.Direction direction;
        T.NodeGroupType ng;


        TermInfo() {
            this.ng = null;
        }

        void setHorTG(short len) {
            if (len == 1)
                this.ng = T.NodeGroupType.valueOf("HORT_SINGLE");
            else if (len == 2)
                this.ng = T.NodeGroupType.valueOf("HORT_DOUBLE");
            else if (len == 4)
                this.ng = T.NodeGroupType.valueOf("HORT_QUAD");
            else
                this.ng = T.NodeGroupType.valueOf("HORT_LONG");
        }

        void setVerTG(short len) {
            if (len == 1)
                this.ng = T.NodeGroupType.valueOf("VERT_SINGLE");
            else if (len == 2)
                this.ng = T.NodeGroupType.valueOf("VERT_DOUBLE");
            else if (len == 4)
                this.ng = T.NodeGroupType.valueOf("VERT_QUAD");
            else
                this.ng = T.NodeGroupType.valueOf("VERT_LONG");
        }
        
        public String toString() {
            return String.format("x:%d y:%d %s %s", x,y, ng.name(), direction.name());
        }

        public short begin() {
            return ng.orientation() == T.Orientation.HORIZONTAL ? x : y;
        }

        public short end() {
            short delta = ng.length();
            short start = ng.orientation() == T.Orientation.HORIZONTAL ? x : y;
            return  (short) (start + (direction == InterconnectInfo.Direction.U ? delta : -delta));
        }
    }

    
    // Currently, the default mode in DelayEstimatorTable is fastMode which use getClosetSrcDstSitePin. 
    private TermInfo getTermInfo(Node node) {
        IntentCode ic = node.getIntentCode();
        TermInfo termInfo = new TermInfo();

        termInfo.x = (short) node.getTile().getTileXCoordinate();
        termInfo.y = (short) node.getTile().getTileYCoordinate();


        String nodeType = node.getWireName();
        // Based on its name, WW1_E should go be horizontal single. However, it go to the north like NN1_E.
        if (nodeType.contains("WW1_E")) {
            nodeType = "NN1_E";
        }

        if (nodeType.startsWith("INT") && (ic == IntentCode.NODE_SINGLE)) {
            // Special for internal single such as INT_X0Y0/INT_INT_SDQ_33_INT_OUT1  - NODE_SINGLE
            // The exact orientation can be found, but it is slow. Setting wrong orientation for internal single has no harm.
            termInfo.direction = InterconnectInfo.Direction.U;
            termInfo.ng = T.NodeGroupType.INTERNAL_SINGLE;
        } else {
            String nodeGroupSide = nodeType.substring(0, nodeType.indexOf('_'));
            switch(nodeGroupSide.charAt(0)) {
            	case 'E':
            		termInfo.direction = InterconnectInfo.Direction.U;
            		termInfo.setHorTG(ic.getLength());
            		break;
            	case 'W':
            		termInfo.direction = InterconnectInfo.Direction.D;
            		termInfo.setHorTG(ic.getLength());
            		break;
            	case 'N':
            		termInfo.direction = InterconnectInfo.Direction.U;
            		termInfo.setVerTG(ic.getLength());
            		break;
            	case 'S':
            		termInfo.direction = InterconnectInfo.Direction.D;
            		termInfo.setVerTG(ic.getLength());
            		break;
            	default:
            		termInfo.direction = InterconnectInfo.Direction.S;
            		switch(ic) {
            			case NODE_PINBOUNCE:
            			case NODE_PINFEED:
            				termInfo.ng = T.NodeGroupType.CLE_IN;
            				break;
            			case NODE_LOCAL:
            				termInfo.ng = T.NodeGroupType.GLOBAL;
            				termInfo.direction = InterconnectInfo.Direction.U;
            				break;
            			default:
            				termInfo.ng = T.NodeGroupType.CLE_OUT;
            		}
            }
        }

        return termInfo;
    }

    @FunctionalInterface
    interface BuildAccumulativeList<T> {
        List<T> apply(List<T> l);
    }


    /**
     * Load input site pin from the model. Need to do only once in ctor.
     * @param tm a timing model
     */
    private void loadInputSitePinDelay(TimingModel tm) {
        inputSitePinDelay = new HashMap<>();

        // Translate from a site pin name to node connected to the site pin.
        Map<String, Short> sitePinDelay = tm.getInputSitePinDelay();
        for (Map.Entry<String,String> nodeSitePin : ictInfo.getNodeToSitePin().entrySet()) {
            inputSitePinDelay.put(nodeSitePin.getKey(), sitePinDelay.get(nodeSitePin.getValue()));
        }
    };


    /**
     * DistanceArray in TimingModel is using INT tile coordinate.
     * Convert the arrays to INT tile coordinate.
     */
    private void buildDistanceArrays(TimingModel tm) {
    	// Somehow I cannot use Function<T,R>. I get "target method is generic" error.
        BuildAccumulativeList<Short> buildAccumulativeList = (list) ->
        {
            // list[i] := d between int tile 1-1 and tile i
            // res [i] := d between int tile 0   and tile i
            short acc = 0;
            List<Short> res = new ArrayList<>();
            for (Short val : list) {
                acc += val;
                res.add(acc);
            }
            return res;
        };

        distArrays = new EnumMap<> (T.Orientation.class);
        for (T.Orientation d : T.Orientation.values()) {
            distArrays.put(d, new EnumMap<> (GroupDelayType.class));
            // Intentionally populated only these types so that accidentally access other types will cause runtime error.
            distArrays.get(d).put(GroupDelayType.SINGLE, new ArrayList<>());
            distArrays.get(d).put(GroupDelayType.DOUBLE, new ArrayList<>());
            distArrays.get(d).put(GroupDelayType.QUAD,   new ArrayList<>());
            distArrays.get(d).put(GroupDelayType.LONG,   new ArrayList<>());
        }

        Map<GroupDelayType, List<Short>> verDistArray = tm.getVerDistArrayInIntTileGrid();
        Map<GroupDelayType, List<Short>> horDistArray = tm.getHorDistArrayInIntTileGrid();

        for (GroupDelayType t : GroupDelayType.values()) {
            distArrays.get(T.Orientation.VERTICAL).put(t, buildAccumulativeList.apply(verDistArray.get(t)));
        }
        for (GroupDelayType t : GroupDelayType.values()) {
                distArrays.get(T.Orientation.HORIZONTAL).put(t, buildAccumulativeList.apply(horDistArray.get(t)));
        }

        numCol = distArrays.get(T.Orientation.HORIZONTAL).get(GroupDelayType.SINGLE).size();
        numRow = distArrays.get(T.Orientation.VERTICAL).get(GroupDelayType.SINGLE).size();
        distArrays.get(T.Orientation.INPUT).put(GroupDelayType.PINFEED,    new ArrayList<>(Collections.nCopies(max(numRow,numCol), (short) 0)));
        distArrays.get(T.Orientation.LOCAL).put(GroupDelayType.PIN_BOUNCE, new ArrayList<>(Collections.nCopies(max(numRow,numCol), (short) 0)));
        distArrays.get(T.Orientation.OUTPUT).put(GroupDelayType.OTHER,     new ArrayList<>(Collections.nCopies(max(numRow,numCol), (short) 0)));
        distArrays.get(T.Orientation.HORIZONTAL).put(GroupDelayType.GLOBAL,new ArrayList<>(Collections.nCopies(max(numRow,numCol), (short) 0)));

        K0 = new EnumMap<>(T.Orientation.class);
        K1 = new EnumMap<>(T.Orientation.class);
        K2 = new EnumMap<>(T.Orientation.class);
        L  = new EnumMap<>(T.Orientation.class);

        K0.put(T.Orientation.HORIZONTAL, tm.getHorK0Coefficients());
        K1.put(T.Orientation.HORIZONTAL, tm.getHorK1Coefficients());
        K2.put(T.Orientation.HORIZONTAL, tm.getHorK2Coefficients());
        L.put( T.Orientation.HORIZONTAL, tm.getHorLCoefficients());

        K0.put(T.Orientation.VERTICAL, tm.getVerK0Coefficients());
        K1.put(T.Orientation.VERTICAL, tm.getVerK1Coefficients());
        K2.put(T.Orientation.VERTICAL, tm.getVerK2Coefficients());
        L.put( T.Orientation.VERTICAL, tm.getVerLCoefficients());

        {
            Map<GroupDelayType, Float> tk0 = new EnumMap<>(GroupDelayType.class);
            tk0.put(GroupDelayType.PINFEED, K0.get(T.Orientation.HORIZONTAL).get(GroupDelayType.SINGLE));
            K0.put(T.Orientation.INPUT, tk0);

            Map<GroupDelayType, Float> tk1 = new EnumMap<>(GroupDelayType.class);
            tk1.put(GroupDelayType.PINFEED, 0f);
            K1.put(T.Orientation.INPUT, tk1);

            Map<GroupDelayType, Float> tk2 = new EnumMap<>(GroupDelayType.class);
            tk2.put(GroupDelayType.PINFEED, 0f);
            K2.put(T.Orientation.INPUT, tk2);

            Map<GroupDelayType, Short> tl = new EnumMap<>(GroupDelayType.class);
            tl.put(GroupDelayType.PINFEED, (short) 0);
            L.put(T.Orientation.INPUT, tl);
        }
        {
            Map<GroupDelayType, Float> tk0 = new EnumMap<>(GroupDelayType.class);
            tk0.put(GroupDelayType.PIN_BOUNCE, K0.get(T.Orientation.HORIZONTAL).get(GroupDelayType.SINGLE));
            K0.put(T.Orientation.LOCAL, tk0);

            Map<GroupDelayType, Float> tk1 = new EnumMap<>(GroupDelayType.class);
            tk1.put(GroupDelayType.PIN_BOUNCE, 0f);
            K1.put(T.Orientation.LOCAL, tk1);

            Map<GroupDelayType, Float> tk2 = new EnumMap<>(GroupDelayType.class);
            tk2.put(GroupDelayType.PIN_BOUNCE, 0f);
            K2.put(T.Orientation.LOCAL, tk2);

            Map<GroupDelayType, Short> tl = new EnumMap<>(GroupDelayType.class);
            tl.put(GroupDelayType.PIN_BOUNCE, (short) 0);
            L.put(T.Orientation.LOCAL, tl);
        }
        {
            Map<GroupDelayType, Float> tk0 = new EnumMap<>(GroupDelayType.class);
            tk0.put(GroupDelayType.OTHER, 0f);
            K0.put(T.Orientation.OUTPUT, tk0);

            Map<GroupDelayType, Float> tk1 = new EnumMap<>(GroupDelayType.class);
            tk1.put(GroupDelayType.OTHER, 0f);
            K1.put(T.Orientation.OUTPUT, tk1);

            Map<GroupDelayType, Float> tk2 = new EnumMap<>(GroupDelayType.class);
            tk2.put(GroupDelayType.OTHER, 0f);
            K2.put(T.Orientation.OUTPUT, tk2);

            Map<GroupDelayType, Short> tl = new EnumMap<>(GroupDelayType.class);
            tl.put(GroupDelayType.OTHER, (short) 0);
            L.put(T.Orientation.OUTPUT, tl);
        }
    }


    /**
     * Compute the delay of the given node group.
     * @param tg       A node group
     * @param begLoc   The beginning location of the node group, in INT tile coordinate
     * @param endLoc   The end location of the node group
     * @return delay of the tg. When useUTurnNodes was set to false, return Short.MAX_VALUE/2 if the node graph is a U-turn.
     *         to indicate the node graph should be ignored.
     */
    short calcTimingGroupDelay(T.NodeGroupType tg, short begLoc, short endLoc, Double dly) {
        int size = (tg.orientation() == T.Orientation.HORIZONTAL) ? numCol : numRow;
        short d = 0;
        List<Short> dArray = distArrays.get(tg.orientation()).get(tg.type());
        if(endLoc >= 0 && endLoc < size) {
        	short st  = dArray.get(begLoc);
        	short sp  = dArray.get(endLoc);
            // Need abs in case the tg is going to the left.
            d   = (short) Math.abs(sp-st);
        }else if (endLoc < 0 ) {
        	if (!useUTurnNodes) 
        		return Short.MAX_VALUE/2;// remove negative delay of u-turn NodeGroups at the device boundaries
        	else {
        		d = (short) (dArray.get(begLoc) - 2 * dArray.get(0) + dArray.get(-endLoc - 1));
        	}
        }else if(endLoc >= size) {
        	if(!useUTurnNodes) {
        		return Short.MAX_VALUE / 2;
        	}else {
        		int index = Math.min(size - 1, endLoc);
            	d = (short) (dArray.get(index) - dArray.get(begLoc));
            	int endIndex = (size - 1) - (index - begLoc) - 1;
            	d += dArray.get(size - 1) - dArray.get(endIndex); 
        	}
        }

        float k0 = K0.get(tg.orientation()).get(tg.type());
        float k1 = K1.get(tg.orientation()).get(tg.type());
        float k2 = K2.get(tg.orientation()).get(tg.type());
        short l  = L .get(tg.orientation()).get(tg.type());

        return (short) (k0 + k1 * l + k2 * d);
    }
}