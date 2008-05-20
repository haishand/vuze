package com.aelitis.azureus.ui.swt.views.skin;

import org.eclipse.swt.SWT;

import org.gudy.azureus2.core3.download.DownloadManager;
import org.gudy.azureus2.ui.swt.Utils;

import com.aelitis.azureus.buddy.impl.VuzeBuddyManager;
import com.aelitis.azureus.core.torrent.PlatformTorrentUtils;
import com.aelitis.azureus.ui.selectedcontent.SelectedContent;
import com.aelitis.azureus.ui.swt.utils.SWTLoginUtils;

public class VuzeShareUtils
{

	private static VuzeShareUtils instance;

	private SharePage sharePage = null;

	public static VuzeShareUtils getInstance() {
		if (null == instance) {
			instance = new VuzeShareUtils();
		}
		return instance;
	}

	public void shareTorrent(final SelectedContent currentContent) {
		if (!VuzeBuddyManager.isEnabled()) {
			Utils.openMessageBox(Utils.findAnyShell(), SWT.OK, "DISABLED", "DUH! DISABLED DOOD");
			return;
		}

		SWTLoginUtils.waitForLogin(new SWTLoginUtils.loginWaitListener() {
			public void loginComplete() {
				if (null != sharePage) {
					try {
						sharePage.setShareItem(currentContent);
					} catch (Exception e) {
					}
				}
			}
		});
	}

	public SharePage getSharePage() {
		return sharePage;
	}

	public void setSharePage(SharePage sharePage) {
		this.sharePage = sharePage;
	}

}
