package com.inova8.remediator;

import java.util.ArrayList;
import java.util.Collection;

public class Linksets extends ArrayList<Linkset>{

	/**
	 * 
	 */
	private static final long serialVersionUID = -5968625127422546735L;

	public Linksets() {
		super();
		// TODO Auto-generated constructor stub
	}

	public Linksets(Collection<? extends Linkset> c) {
		super(c);
		// TODO Auto-generated constructor stub
	}

	public void updateStatistics() {
		for (Linkset linkset : this) {
			linkset.updatePartitionStatistics();
		}
	}
}