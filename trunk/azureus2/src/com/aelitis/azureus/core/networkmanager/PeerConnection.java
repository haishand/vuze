/*
 * Created on May 8, 2004
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

package com.aelitis.azureus.core.networkmanager;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Represents a peer transport connection (eg. a network socket).
 */
public interface PeerConnection {
  
  /**
   * Get a textual description for this transport.
   * @return description
   */
  public String getDescription();
  
  /**
   * Read data from the transport into the given buffer.
   * @param buffer
   * @return number of bytes read
   * @throws IOException
   */
  public int read( ByteBuffer buffer ) throws IOException;
    
  /**
   * Write data to the transport from the given buffers.
   * NOTE: Works like GatheringByteChannel.
   * @param buffers th buffers from which bytes are to be retrieved
   * @param array_offset offset within the buffer array of the first buffer from which bytes are to be retrieved
   * @param array_length maximum number of buffers to be accessed
   * @return number of bytes written
   * @throws IOException
   */
  public long write( ByteBuffer[] buffers, int array_offset, int array_length ) throws IOException;
  
  
}
