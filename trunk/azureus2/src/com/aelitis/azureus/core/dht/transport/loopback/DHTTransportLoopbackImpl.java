/*
 * Created on 12-Jan-2005
 * Created by Paul Gardner
 * Copyright (C) 2004 Aelitis, All Rights Reserved.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA  02111-1307, USA.
 * 
 * AELITIS, SARL au capital de 30,000 euros
 * 8 Allee Lenotre, La Grille Royale, 78600 Le Mesnil le Roi, France.
 *
 */

package com.aelitis.azureus.core.dht.transport.loopback;

import java.util.*;
import java.io.*;

import org.gudy.azureus2.core3.util.*;

import com.aelitis.azureus.core.dht.transport.*;
import com.aelitis.azureus.core.dht.transport.util.DHTTransportRequestCounter;
import com.aelitis.azureus.core.dht.transport.util.DHTTransportStatsImpl;

/**
 * @author parg
 *
 */

public class 
DHTTransportLoopbackImpl
	implements DHTTransport
{
	public static		int		VERSION			= 1;
	
	public static 		int		LATENCY			= 0;
	public static		int		FAIL_PERCENTAGE	= 0;
		
	public static void
	setLatency(
		int	_latency )
	{
		LATENCY	= _latency;
	}
	
	public static void
	setFailPercentage(
		int	p )
	{
		FAIL_PERCENTAGE	= p;
	}
	
	private static long	node_id_seed_next	= 0;
	private static Map	node_map	= new HashMap();
	
	private static List	dispatch_queue = new ArrayList();
	private static AESemaphore	dispatch_queue_sem	= new AESemaphore("DHTTransportLoopback" );
	

	static{
		AEThread	dispatcher = 
			new AEThread("DHTTransportLoopback")
			{
				public void
				runSupport()
				{
					while(true){
						
						dispatch_queue_sem.reserve();
						
						Runnable	r;
						
						synchronized( dispatch_queue ){
							
							r = (Runnable)dispatch_queue.remove(0);
						}
						
						if ( LATENCY > 0 ){
							
							try{
								Thread.sleep( LATENCY );
								
							}catch( Throwable e ){
								
							}
						}
						
						r.run();
					}
				}
			};
			
			dispatcher.start();
	}
	
	private byte[]				node_id;
	private DHTTransportContact	local_contact;
	
	private int			id_byte_length;
	
	private DHTTransportRequestHandler		request_handler;
	
	private DHTTransportStatsImpl	stats = new DHTTransportLoopbackStatsImpl();

	private List	listeners = new ArrayList();
	
	public static synchronized DHTTransportStats
	getOverallStats()
	{
		DHTTransportStatsImpl	overall_stats = new DHTTransportLoopbackStatsImpl();
		
		Iterator	it = node_map.values().iterator();
		
		while( it.hasNext()){
			
			overall_stats.add((DHTTransportStatsImpl)((DHTTransportLoopbackImpl)it.next()).getStats());
		}
		
		return( overall_stats );
	}
	
	public
	DHTTransportLoopbackImpl(
		int							_id_byte_length )
	{	
		id_byte_length	= _id_byte_length;
		
		synchronized( DHTTransportLoopbackImpl.class ){
			
			byte[]	temp = new SHA1Hasher().calculateHash( ( "" + ( node_id_seed_next++ )).getBytes());
			
			node_id	= new byte[id_byte_length];
			
			System.arraycopy( temp, 0, node_id, 0, id_byte_length );
			
			node_map.put( new HashWrapper( node_id ), this );
			
			local_contact	= new DHTTransportLoopbackContactImpl( this, node_id );
		}
	}
	
	public DHTTransportContact
	getLocalContact()
	{
		return( local_contact );
	}
	
	protected DHTTransportLoopbackImpl
	findTarget(
		byte[]		id )
	{
		synchronized( DHTTransportLoopbackImpl.class ){
			
			return((DHTTransportLoopbackImpl)node_map.get( new HashWrapper( id )));
		}
	}
	
	public void
	setRequestHandler(
		DHTTransportRequestHandler	_request_handler )
	{
		request_handler = new DHTTransportRequestCounter( _request_handler, stats );

	}
	
	protected DHTTransportRequestHandler
	getRequestHandler()
	{
		return( request_handler );
	}
	
	public void
	exportContact(
		DHTTransportContact	contact,
		DataOutputStream	os )
	
		throws IOException
	{
		os.writeInt( VERSION );
		
		os.writeInt( id_byte_length );
		
		os.write( contact.getID());
	}
	
	public DHTTransportContact
	importContact(
		DataInputStream		is )
	
		throws IOException
	{
		int	version = is.readInt();
		
		if ( version != VERSION ){
			
			throw( new IOException( "Unsuported version" ));

		}
		int	id_len	= is.readInt();
		
		if ( id_len != id_byte_length ){
			
			throw( new IOException( "Imported contact has incorrect ID length" ));
		}
		
		byte[]	id = new byte[id_byte_length];
		
		is.read( id );
		
		DHTTransportContact contact = new DHTTransportLoopbackContactImpl( this, id );
		
		request_handler.contactImported( contact );
		
		return( contact );
	}
	
	protected void
	run(
		final AERunnable	r )
	{
		synchronized( dispatch_queue ){
			
			dispatch_queue.add( r );
		}
		
		dispatch_queue_sem.release();
	}
	
	public DHTTransportStats
	getStats()
	{
		return( stats );
	}
	
		// transport
	
		// PING 
	
	public void
	sendPing(
		final DHTTransportContact			contact,
		final DHTTransportReplyHandler		handler )
	{
		AERunnable	runnable = 
			new AERunnable()
			{
				public void
				runSupport()
				{
					sendPingSupport( contact, handler );
				}
			};
		
		run( runnable );
	}
	
	public void
	sendPingSupport(
		DHTTransportContact			contact,
		DHTTransportReplyHandler	handler )
	{
		DHTTransportLoopbackImpl	target = findTarget( contact.getID());
		
		stats.pingSent();
		
		if ( target == null || triggerFailure()){
		
			stats.pingFailed();
			
			handler.failed(contact);
			
		}else{
			
			stats.pingOK();
			
			target.getRequestHandler().pingRequest( new DHTTransportLoopbackContactImpl( target, node_id ));
			
			handler.pingReply(contact);
		}
	}
		
		// STATS
	
	public void
	sendStats(
		final DHTTransportContact			contact,
		final DHTTransportReplyHandler		handler )
	{
		AERunnable	runnable = 
			new AERunnable()
			{
				public void
				runSupport()
				{
					sendStatsSupport( contact, handler );
				}
			};
		
		run( runnable );
	}
	
	public void
	sendStatsSupport(
		DHTTransportContact			contact,
		DHTTransportReplyHandler	handler )
	{
		DHTTransportLoopbackImpl	target = findTarget( contact.getID());
		
		stats.statsSent();
		
		if ( target == null || triggerFailure()){
		
			stats.statsFailed();
			
			handler.failed(contact);
			
		}else{
			
			stats.statsOK();
			
			DHTTransportFullStats res = target.getRequestHandler().statsRequest( new DHTTransportLoopbackContactImpl( target, node_id ));
			
			handler.statsReply(contact,res);
		}
	}
		
		// STORE
	
	public void
	sendStore(
		final DHTTransportContact		contact,
		final DHTTransportReplyHandler	handler,
		final byte[]					key,
		final DHTTransportValue			value )
	{
		AERunnable	runnable = 
			new AERunnable()
			{
				public void
				runSupport()
				{
					sendStoreSupport( contact, handler, key, value );
				}
			};
		
		run( runnable );
	}
	
	public void
	sendStoreSupport(
		DHTTransportContact			contact,
		DHTTransportReplyHandler	handler,
		byte[]						key,
		DHTTransportValue			value )
	{
		DHTTransportLoopbackImpl	target = findTarget( contact.getID());
		
		stats.storeSent();
		
		if ( target == null  || triggerFailure()){
		
			stats.storeFailed();
			
			handler.failed(contact);
			
		}else{
			
			stats.storeOK();
			
			target.getRequestHandler().storeRequest( 
					new DHTTransportLoopbackContactImpl( target, node_id ),
					key, value );
			
			handler.storeReply( contact );
		}
	}
	
		// FIND NODE
	
	public void
	sendFindNode(
		final DHTTransportContact		contact,
		final DHTTransportReplyHandler	handler,
		final byte[]					nid )
	{
		AERunnable	runnable = 
			new AERunnable()
			{
				public void
				runSupport()
				{
					sendFindNodeSupport( contact, handler, nid );
				}
			};
		
		run( runnable );
	}
	
	public void
	sendFindNodeSupport(
		DHTTransportContact			contact,
		DHTTransportReplyHandler	handler,
		byte[]						nid )
	{
		DHTTransportLoopbackImpl	target = findTarget( contact.getID());
		
		stats.findNodeSent();
		
		if ( target == null  || triggerFailure() ){
		
			stats.findNodeFailed();
			
			handler.failed(contact);
			
		}else{
			
			stats.findNodeOK();
			
			DHTTransportContact[] res =
				target.getRequestHandler().findNodeRequest( 
					new DHTTransportLoopbackContactImpl( target, node_id ),
					nid );
			
			DHTTransportContact[] trans_res = new DHTTransportContact[res.length];
																	  														  
			for (int i=0;i<res.length;i++){
				
				trans_res[i] = new DHTTransportLoopbackContactImpl( this, res[i].getID());
			}
			
			handler.findNodeReply( contact, trans_res );
		}
	}
	
		// FIND VALUE
	
	public void
	sendFindValue(
		final DHTTransportContact		contact,
		final DHTTransportReplyHandler	handler,
		final byte[]					key )
	{
		AERunnable	runnable = 
			new AERunnable()
			{
				public void
				runSupport()
				{
					sendFindValueSupport( contact, handler, key );
				}
			};
		
		run( runnable );
	}
	
	public void
	sendFindValueSupport(
		DHTTransportContact			contact,
		DHTTransportReplyHandler	handler,
		byte[]						key )
	{
		DHTTransportLoopbackImpl	target = findTarget( contact.getID());
		
		stats.findValueSent();
		
		if ( target == null  || triggerFailure()){
		
			stats.findValueFailed();
			
			handler.failed(contact);
			
		}else{
			
			stats.findValueOK();
			
			Object o_res =
				target.getRequestHandler().findValueRequest( 
					new DHTTransportLoopbackContactImpl( target, node_id ),
					key );
			
			if ( o_res instanceof DHTTransportContact[]){
				
				DHTTransportContact[]	res  = (DHTTransportContact[])o_res;
				
				DHTTransportContact[] trans_res = new DHTTransportContact[res.length];
				  
				for (int i=0;i<res.length;i++){
				
					trans_res[i] = new DHTTransportLoopbackContactImpl( this, res[i].getID());
				}

				handler.findValueReply( contact, trans_res );
				
			}else{
				
				handler.findValueReply( contact, (DHTTransportValue)o_res );
			}
		}
	}
	
	protected boolean
	triggerFailure()
	{
		return( Math.random()*100 < FAIL_PERCENTAGE );
	}
	
	public void
	addListener(
		DHTTransportListener	l )
	{
		listeners.add(l);
	}
	
	public void
	removeListener(
		DHTTransportListener	l )
	{
		listeners.remove(l);
	}
}
