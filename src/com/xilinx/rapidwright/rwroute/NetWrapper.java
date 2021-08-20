/*
 * 
 * Copyright (c) 2021 Ghent University. 
 * All rights reserved.
 *
 * Author: Yun Zhou, Ghent University.
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

package com.xilinx.rapidwright.rwroute;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.xilinx.rapidwright.design.Net;
import com.xilinx.rapidwright.design.SitePinInst;

/**
 * A wrapper class of {@link Net} with additional information for the router.
 */
public class NetWrapper{
	/** A unique index for a NetWrapepr Object*/
	private int id;
	/** The associated {@link Net} Object */
	private Net net;
	/** A list of {@link Connection} Objects of the net */
	private List<Connection> connections;
	/** Geometric center coordinates */
	private float xCenter;
	private float yCenter;
	/** The half-perimeter wirelength */
	private short doubleHpwl;
	/** A flag to indicate if the source has been swapped */
	private boolean sourceChanged;
	/** Stores the old source SitePinInst after output pin swapping */
	private SitePinInst oldSource;
	
	public NetWrapper(int id, short bbRange, Net net){
		this.id = id;
		this.net = net;
		this.connections = new ArrayList<>();
		this.setSourceChanged(false, null);
	}
	
	public void computeHPWLAndCenterCoordinates(short bbRange){
		short xMin = 1<<10;
		short xMax = 0;
		short yMin = 1<<10;
		short yMax = 0;
		float xSum = 0;
		float ySum = 0;		
		List<Short> xArray = new ArrayList<>();
		List<Short> yArray = new ArrayList<>();
		
		boolean sourceRnodeAdded = false;	
		for(Connection c : this.connections) {
			if(c.isDirect()) continue;
			short x = 0;
			short y = 0;
			if(!sourceRnodeAdded) {
				x = c.getSourceRnode().getEndTileXCoordinate();
				y = c.getSourceRnode().getEndTileYCoordinate();
				xArray.add(x);
				yArray.add(y);
				xSum += x;
				ySum += y;		
				sourceRnodeAdded = true;
			}	
			x = c.getSinkRnode().getEndTileXCoordinate();
			y = c.getSinkRnode().getEndTileYCoordinate();
			xArray.add(x);
			yArray.add(y);
			xSum += x;
			ySum += y;	
		}
		
		Collections.sort(xArray);
		Collections.sort(yArray);
		xMin = xArray.get(0);
		xMax = xArray.get(xArray.size() - 1);
		yMin = yArray.get(0);
		yMax = yArray.get(xArray.size() - 1);
		
		this.setDoubleHpwl((short) ((xMax - xMin + 1 + yMax - yMin + 1) * 2));
		this.setXCenter(xSum / xArray.size());
		this.setYCenter(ySum / yArray.size());
	}
	
	public Net getNet(){
		return this.net;
	}
	
	public int getId() {
		return id;
	}
	
	public void addCons(Connection c){
		this.connections.add(c);	
	}
	
	public List<Connection> getConnection(){
		return this.connections;
	}

	public short getDoubleHpwl() {
		return doubleHpwl;
	}

	public void setDoubleHpwl(short hpwl) {
		this.doubleHpwl = hpwl;
	}

	public boolean isSourceChanged() {
		return sourceChanged;
	}

	public void setSourceChanged(boolean sourceChanged, SitePinInst oldSource) {
		this.sourceChanged = sourceChanged;
		this.setOldSource(oldSource);
	}

	public float getYCenter() {
		return yCenter;
	}

	public void setYCenter(float yCenter) {
		this.yCenter = yCenter;
	}

	public float getXCenter() {
		return xCenter;
	}

	public void setXCenter(float xCenter) {
		this.xCenter = xCenter;
	}
	
	public SitePinInst getOldSource() {
		return oldSource;
	}

	public void setOldSource(SitePinInst oldSource) {
		this.oldSource = oldSource;
	}
	
	@Override
	public int hashCode(){
		return this.id;
	}
}
