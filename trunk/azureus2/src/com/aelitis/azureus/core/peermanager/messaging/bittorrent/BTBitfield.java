/*
 * Created on Apr 30, 2004
 * Created by Alon Rohter
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

package com.aelitis.azureus.core.peermanager.messaging.bittorrent;

import java.nio.ByteBuffer;

import org.gudy.azureus2.core3.util.*;



/**
 * BitTorrent bitfield message.
 */
public class BTBitfield implements BTProtocolMessage {
  private final DirectByteBuffer[] buffer;

  
  public BTBitfield( ByteBuffer bitfield ) {
    buffer = new DirectByteBuffer[] { new DirectByteBuffer( bitfield ) };
  }
  

  public String getID() {  return BTProtocolMessage.ID_BT_BITFIELD;  }
  
  public byte getVersion() {  return BTProtocolMessage.BT_DEFAULT_VERSION;  }
    
  public String getDescription() {  return BTProtocolMessage.ID_BT_BITFIELD;  }
  
  public DirectByteBuffer[] getData() {  return buffer;  }

  
  

  
  
  

  
}
