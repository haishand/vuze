/**
 * Created on Sep 12, 2008
 *
 * Copyright 2008 Vuze, Inc.  All rights reserved.
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; version 2 of the License only.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307  USA 
 */

package com.aelitis.azureus.ui.selectedcontent;

import java.util.Map;

import org.gudy.azureus2.core3.util.LightHashMap;

/**
 * @author TuxPaper
 * @created Sep 12, 2008
 *
 */
public class DownloadUrlInfo
{
	private String dlURL;

	private String referer;

	private Map requestProperties;

	private Map additionalProperties = null;

	/**
	 * @param url
	 */
	public DownloadUrlInfo(String url) {
		setDownloadURL(url);
	}

	/**
	 * @since 3.1.1.1
	 */
	public String getDownloadURL() {
		return dlURL;
	}

	/**
	 * @since 3.1.1.1
	 */
	public void setDownloadURL(String dlURL) {
		this.dlURL = dlURL;
	}

	public void setReferer(String referer) {
		this.referer = referer;
	}

	public String getReferer() {
		return referer;
	}

	/**
	 * @return
	 *
	 * @since 3.1.1.1
	 */
	public Map getRequestProperties() {
		return requestProperties;
	}

	/**
	 * @param requestProperties the requestProperties to set
	 */
	public void setRequestProperties(Map requestProperties) {
		this.requestProperties = requestProperties;
	}

	/**
	 * @param additionalProperties the additionalProperties to set
	 */
	public void setAdditionalProperty(String key, Object value) {
		if (additionalProperties == null) {
			additionalProperties = new LightHashMap(1);
		}
		additionalProperties.put(key, value);
	}

	public void setAdditionalProperties(Map mapToCopy) {
		if (additionalProperties == null) {
			additionalProperties = new LightHashMap(1);
		}
		additionalProperties.putAll(mapToCopy);
	}


	/**
	 * @return the additionalProperties
	 */
	public Map getAdditionalProperties() {
		return additionalProperties;
	}
}
