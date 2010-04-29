/*
  Copyright (c) 2010, Oracle and/or its affiliates. All rights reserved.

  The MySQL Connector/J is licensed under the terms of the GPL,
  like most MySQL Connectors. There are special exceptions to the
  terms and conditions of the GPL as it is applied to this software,
  see the FLOSS License Exception available on mysql.com.

  This program is free software; you can redistribute it and/or
  modify it under the terms of the GNU General Public License as
  published by the Free Software Foundation; version 2 of the
  License.

  This program is distributed in the hope that it will be useful,  
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software
  Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
  02110-1301 USA
 
 */
package com.mysql.jdbc;

import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ConnectionGroup {
	private String groupName;
	private long connections = 0;
	private long activeConnections = 0;
	private HashMap connectionProxies = new HashMap();
	private Set hostList = new HashSet();
	private boolean isInitialized = false;
	private long closedProxyTotalPhysicalConnections = 0;
	private long closedProxyTotalTransactions = 0;
	private int activeHosts = 0;
	private Set closedHosts = new HashSet();

	ConnectionGroup(String groupName){
		this.groupName = groupName;
	}
	
	public long registerConnectionProxy(LoadBalancingConnectionProxy proxy, List hostList){
		long currentConnectionId;

		synchronized (this){
			if(!this.isInitialized){
				this.hostList.addAll(hostList);
				this.isInitialized = true;
				this.activeHosts = hostList.size();
			}
			currentConnectionId = ++connections;
			this.connectionProxies.put(new Long(currentConnectionId), proxy);
		}
		this.activeConnections++;
		
		return currentConnectionId;
		
	}
	
	/* (non-Javadoc)
	 * @see com.mysql.jdbc.ConnectionGroupMBean#getGroupName()
	 */
	public String getGroupName(){
		return this.groupName;
	}
	
	/* (non-Javadoc)
	 * @see com.mysql.jdbc.ConnectionGroupMBean#getInitialHostList()
	 */
	public Collection getInitialHosts(){
		return this.hostList;
	}
	
	/* (non-Javadoc)
	 * @see com.mysql.jdbc.ConnectionGroupMBean#getActiveHostCount()
	 */
	public int getActiveHostCount(){
		return this.activeHosts;
	}
	
	
	public Collection getClosedHosts(){
		return this.closedHosts;
	}
	
	
	/* (non-Javadoc)
	 * @see com.mysql.jdbc.ConnectionGroupMBean#getTotalLogicalConnectionCount()
	 */
	public long getTotalLogicalConnectionCount(){
		return this.connections;
	}
	
	/* (non-Javadoc)
	 * @see com.mysql.jdbc.ConnectionGroupMBean#getActiveLogicalConnectionCount()
	 */
	public long getActiveLogicalConnectionCount(){
		return this.activeConnections;
	}
	/* (non-Javadoc)
	 * @see com.mysql.jdbc.ConnectionGroupMBean#getActivePhysicalConnectionCount()
	 */
	public long getActivePhysicalConnectionCount(){
		long connections = 0;
		Map proxyMap = new HashMap();
		synchronized(this.connectionProxies){
			proxyMap.putAll(this.connectionProxies);
		}
		
		Set proxyKeys = proxyMap.keySet();
		
		Iterator i = proxyKeys.iterator();
		while(i.hasNext()){
			LoadBalancingConnectionProxy proxy = (LoadBalancingConnectionProxy) proxyMap.get(i.next());
			connections += proxy.getActivePhysicalConnectionCount();
			
		}
		return connections;
	}
	
	/* (non-Javadoc)
	 * @see com.mysql.jdbc.ConnectionGroupMBean#getTotalPhysicalConnectionCount()
	 */
	public long getTotalPhysicalConnectionCount(){
		long allConnections = this.closedProxyTotalPhysicalConnections;
		Map proxyMap = new HashMap();
		synchronized(this.connectionProxies){
			proxyMap.putAll(this.connectionProxies);
		}
		
		Set proxyKeys = proxyMap.keySet();
		
		Iterator i = proxyKeys.iterator();
		while(i.hasNext()){
			LoadBalancingConnectionProxy proxy = (LoadBalancingConnectionProxy) proxyMap.get(i.next());
			allConnections += proxy.getTotalPhysicalConnectionCount();
			
		}
		return allConnections;
	}
	
	/* (non-Javadoc)
	 * @see com.mysql.jdbc.ConnectionGroupMBean#getTotalTransactionCount()
	 */
	public long getTotalTransactionCount(){
		// need to account for closed connection proxies
		long transactions = this.closedProxyTotalTransactions;
		Map proxyMap = new HashMap();
		synchronized(this.connectionProxies){
			proxyMap.putAll(this.connectionProxies);
		}
		
		Set proxyKeys = proxyMap.keySet();
		
		Iterator i = proxyKeys.iterator();
		while(i.hasNext()){
			LoadBalancingConnectionProxy proxy = (LoadBalancingConnectionProxy) proxyMap.get(i.next());
			transactions += proxy.getTransactionCount();
			
		}
		return transactions;		
	}

	
	public void closeConnectionProxy(LoadBalancingConnectionProxy proxy){
		this.activeConnections--;
		this.connectionProxies.remove(new Long(proxy.getConnectionGroupProxyID()));
		this.closedProxyTotalPhysicalConnections += proxy.getTotalPhysicalConnectionCount();
		this.closedProxyTotalTransactions += proxy.getTransactionCount();
		
	}
	
	public void removeHost(String host) throws SQLException {
		removeHost(host, false);
	}
	
	public void removeHost(String host, boolean killExistingConnections) throws SQLException {
		this.removeHost(host, killExistingConnections, true);

	}
	/* (non-Javadoc)
	 * @see com.mysql.jdbc.ConnectionGroupMBean#removeHost(java.lang.String, boolean, boolean)
	 */
	public synchronized void removeHost(String host, boolean killExistingConnections, boolean waitForGracefulFailover) throws SQLException {
		if(this.activeHosts == 1){
			throw SQLError.createSQLException("Cannot remove host, only one configured host active.", null);
		}
		
		if(this.hostList.remove(host)){
			this.activeHosts--;
		} else {
			throw SQLError.createSQLException("Host is not configured: " + host, null);
		}
		
		if(killExistingConnections){
			// make a local copy to keep synchronization overhead to minimum
			Map proxyMap = new HashMap();
			synchronized(this.connectionProxies){
				proxyMap.putAll(this.connectionProxies);
			}
			
			Set proxyKeys = proxyMap.keySet();
			
			Iterator i = proxyKeys.iterator();
			while(i.hasNext()){
				LoadBalancingConnectionProxy proxy = (LoadBalancingConnectionProxy) proxyMap.get(i.next());
				if(waitForGracefulFailover){
					proxy.removeHostWhenNotInUse(host);
				} else {
					proxy.removeHost(host);
				}
			}			
		}
		this.closedHosts.add(host);
	}
	
	
	public void addHost(String host){
		addHost(host, false);
	}
	
	
	/* (non-Javadoc)
	 * @see com.mysql.jdbc.ConnectionGroupMBean#addHost(java.lang.String, boolean)
	 */
	public void addHost(String host, boolean forExisting){
		
		synchronized(this){
			if(this.hostList.add(host)){
				this.activeHosts++;
			}
		}
		// all new connections will have this host
		if(!forExisting){
			return;
		}
		
		
		// make a local copy to keep synchronization overhead to minimum
		Map proxyMap = new HashMap();
		synchronized(this.connectionProxies){
			proxyMap.putAll(this.connectionProxies);
		}
		
		Set proxyKeys = proxyMap.keySet();
		
		Iterator i = proxyKeys.iterator();
		while(i.hasNext()){
			LoadBalancingConnectionProxy proxy = (LoadBalancingConnectionProxy) proxyMap.get(i.next());
			proxy.addHost(host);
		}
		
	}
	
}