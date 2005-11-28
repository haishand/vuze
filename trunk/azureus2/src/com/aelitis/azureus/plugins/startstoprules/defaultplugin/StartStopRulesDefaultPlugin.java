/*
 * File    : StartStopRulesDefaultPlugin.java
 * Created : 12-Jan-2004
 * By      : TuxPaper
 *
 * Azureus - a Java Bittorrent client
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details ( see the LICENSE file ).
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package com.aelitis.azureus.plugins.startstoprules.defaultplugin;

import java.util.*;

import org.gudy.azureus2.core3.config.COConfigurationListener;
import org.gudy.azureus2.core3.config.COConfigurationManager;
import org.gudy.azureus2.core3.util.AEMonitor;
import org.gudy.azureus2.core3.util.Debug;
import org.gudy.azureus2.core3.util.SystemTime;
import org.gudy.azureus2.plugins.Plugin;
import org.gudy.azureus2.plugins.PluginConfig;
import org.gudy.azureus2.plugins.PluginInterface;
import org.gudy.azureus2.plugins.PluginListener;
import org.gudy.azureus2.plugins.disk.DiskManagerFileInfo;
import org.gudy.azureus2.plugins.download.*;
import org.gudy.azureus2.plugins.logging.LoggerChannel;
import org.gudy.azureus2.plugins.torrent.Torrent;
import org.gudy.azureus2.plugins.ui.UIInstance;
import org.gudy.azureus2.plugins.ui.UIManagerListener;
import org.gudy.azureus2.plugins.ui.menus.MenuItem;
import org.gudy.azureus2.plugins.ui.menus.MenuItemListener;
import org.gudy.azureus2.plugins.ui.tables.TableColumn;
import org.gudy.azureus2.plugins.ui.tables.TableContextMenuItem;
import org.gudy.azureus2.plugins.ui.tables.TableManager;
import org.gudy.azureus2.plugins.ui.tables.TableRow;
import org.gudy.azureus2.ui.swt.plugins.UISWTInstance;

import com.aelitis.azureus.plugins.startstoprules.defaultplugin.ui.swt.StartStopRulesDefaultPluginSWTUI;

/** Handles Starting and Stopping of torrents.
 *
 * TODO: RANK_TIMED is quite a hack and is spread all over.  It needs to be
 *       redone, probably with a timer on each seeding torrent which triggers
 *       when time is up and it needs to stop.
 *       
 * BUG: When "AutoStart 0 Peers" is on, and minSpeedForActivelySeeding is 
 *      enabled, the 0 peer torrents will continuously switch from seeding to 
 *      queued, probably due to the connection attempt registering speed.
 *      This might be fixed by the "wait XX ms before switching active state"
 *      code.
 *      
 * Other Notes:
 * "CD" is often used to refer to "Seed" or "Seeding", because "C" sounds like
 * "See"
 * 
 * 
 * If we had a function like Download.needsChecking(), we could
 * write something in process to handle only one check at a time, while
 * still allowing other torrents to start and stop.  As it stands,
 * we have to suspend start and stops until the check is complete.
 *
 * There are cases where two torrents will start at the same time and need
 * re-checking.  We check that in stateChanged (see comments there).  A
 * Download.needsChecking() would also eliminate the need for that code.
 */
public class StartStopRulesDefaultPlugin
       implements Plugin, COConfigurationListener
{
  // for debugging
  private static final String sStates = " WPRDS.XEQ";

  /** Do not rank completed torrents */  
  public static final int RANK_NONE = 0;
  /** Rank completed torrents using Seeds:Peer Ratio */  
  public static final int RANK_SPRATIO = 1;
  /** Rank completed torrents using Seed Count method */  
  public static final int RANK_SEEDCOUNT = 2;
  /** Rank completed torrents using a timed rotation of minTimeAlive */
  public static final int RANK_TIMED = 3;
  
  /** All of the First Priority rules must match */
  public static final int FIRSTPRIORITY_ALL = 0;
  /** Any of the First Priority rules must match */
  public static final int FIRSTPRIORITY_ANY = 1;
  
  // Seeding Rank (SR) Limits and Values
  public static final int SR_INCOMPLETE_ENDS_AT      = 1000000000; // billion
  public static final int SR_TIMED_QUEUED_ENDS_AT    =   10000000;
  public static final int SR_NOTQUEUED       = -2;
  public static final int SR_SPRATIOMET	  = -3;
  public static final int SR_RATIOMET        = -4;
  public static final int SR_NUMSEEDSMET     = -5;
  public static final int SR_FP0PEERS    	  = -6;
  public static final int SR_0PEERS          = -7;
  public static final int SR_SHARERATIOMET   = -8;
  
  private static final int FORCE_ACTIVE_FOR = 30000;

  private static final int FORCE_CHECK_PERIOD				= 30000;
  private static final int CHECK_FOR_GROSS_CHANGE_PERIOD	= 30000;
  private static final int PROCESS_CHECK_PERIOD				= 500;
  
  /** Wait xx ms before starting completed torrents (so scrapes can come in) */
  private static final int MIN_SEEDING_STARTUP_WAIT = 20000;
  /** Wait at least xx ms for first scrape, before starting completed torrents */
  private static final int MIN_FIRST_SCRAPE_WAIT = 90000;
  
  private PluginInterface     plugin_interface;
  private PluginConfig        plugin_config;
  private DownloadManager     download_manager;
  private Timer               changeCheckerTimer;
  /** Used only for RANK_TIMED. Recalculate ranks on a timer */
  private TimerTask           recalcSeedingRanksTask;

  /** Map to relate downloadData to a Download */  
  private Map downloadDataMap = AEMonitor.getSynchronisedMap(new HashMap());

  private volatile boolean         closingDown;
  private volatile boolean         somethingChanged;

  private LoggerChannel   log;
  private long startedOn;

  // Config Settings
  private int minPeersToBoostNoSeeds;
  private int minSpeedForActiveDL;
  private int minSpeedForActiveSeeding;
  // count x peers as a full copy, but..
  private int numPeersAsFullCopy;
  // don't count x peers as a full copy if seeds below
  private int iFakeFullCopySeedStart;
  private int _maxActive;
  private boolean _maxActiveWhenSeedingEnabled;
  private int _maxActiveWhenSeeding;
  
  private int maxDownloads;

  // Ignore torrent if seed count is at least..
  private int     iIgnoreSeedCount;
  // Ignore even when First Priority
  private boolean bIgnore0Peers;
  private int     iIgnoreShareRatio;
  private int iIgnoreShareRatio_SeedStart;
  private int     iIgnoreRatioPeers;
  private int iIgnoreRatioPeers_SeedStart;

  private int iRankType = -1;
  private int iRankTypeSeedFallback;
  private boolean bAutoReposition;
  private long minTimeAlive;
  
  private boolean bPreferLargerSwarms;
  private boolean bDebugLog;
  private TableContextMenuItem debugMenuItem = null;
  
  private int minQueueingShareRatio;
  private int iFirstPriorityType;
  private int iFirstPrioritySeedingMinutes;
  private int iFirstPriorityDLMinutes;
  // Ignore First Priority
  private int iFirstPriorityIgnoreSPRatio;
  private boolean bFirstPriorityIgnore0Peer;
  
  private boolean bAutoStart0Peers;
  private int iMaxUploadSpeed;

  private TableColumn seedingRankColumn;

  private AEMonitor		this_mon	= new AEMonitor( "StartStopRules" );
  

  public void initialize(PluginInterface _plugin_interface) {
    startedOn = SystemTime.getCurrentTime();
    changeCheckerTimer = new Timer(true);

    plugin_interface  = _plugin_interface;

	plugin_interface.getPluginProperties().setProperty( "plugin.version", 	"1.0" );
	plugin_interface.getPluginProperties().setProperty( "plugin.name", 		"Start/Stop Rules" );

    plugin_interface.addListener(new PluginListener() {
      public void initializationComplete() { /* not implemented */ }

      public void 
	  closedownInitiated() 
      {
        closingDown = true;
      
        	// we don't want to go off recalculating stuff when config is saved on closedown
        
        COConfigurationManager.removeListener(StartStopRulesDefaultPlugin.this);
      }

      public void closedownComplete() { /* not implemented */ }
    });

    log = plugin_interface.getLogger().getChannel("StartStopRules");
    log.log( LoggerChannel.LT_INFORMATION, "Default StartStopRules Plugin Initialisation" );

    COConfigurationManager.addListener(this);

    plugin_config = plugin_interface.getPluginconfig();

    try {
      TableManager tm = plugin_interface.getUIManager().getTableManager();
      seedingRankColumn = tm.createColumn(TableManager.TABLE_MYTORRENTS_COMPLETE,
                                          "SeedingRank");
      seedingRankColumn.initialize(TableColumn.ALIGN_TRAIL, TableColumn.POSITION_LAST,
                                   80, TableColumn.INTERVAL_LIVE);
  
      SeedingRankColumnListener columnListener = new SeedingRankColumnListener(downloadDataMap, plugin_config);
      seedingRankColumn.addCellRefreshListener(columnListener);
      tm.addColumn(seedingRankColumn);
      
      plugin_interface.getUIManager().addUIListener(
			new UIManagerListener()
			{
				public void
				UIAttached(
					UIInstance		instance )
				{
					if ( instance instanceof UISWTInstance ){
						
						new StartStopRulesDefaultPluginSWTUI( plugin_interface );
					}
				}
				
				public void
				UIDetached(
					UIInstance		instance )
				{
					
				}
			});
     } catch( Throwable e ){
    	Debug.printStackTrace( e );
    }
    reloadConfigParams();

    download_manager = plugin_interface.getDownloadManager();
    download_manager.addListener(new StartStopDMListener());
    
    changeCheckerTimer.schedule(new ChangeCheckerTimerTask(), 10000, CHECK_FOR_GROSS_CHANGE_PERIOD );
    changeCheckerTimer.schedule(new ChangeFlagCheckerTask(), 10000, PROCESS_CHECK_PERIOD );
  }
  
  private void recalcAllSeedingRanks(boolean force) {
  	if ( closingDown ){
  		return;
  	}
  	
  	try{
  		this_mon.enter();
  	
	    downloadData[] dlDataArray = 
	      (downloadData[])downloadDataMap.values().toArray(new downloadData[0]);
	
	    // Check Group #1: Ones that always should run since they set things
	    for (int i = 0; i < dlDataArray.length; i++) {
	      if (force)
	        dlDataArray[i].getDownloadObject().setSeedingRank(0);
	      dlDataArray[i].recalcSeedingRank();
	    }
  	}finally{
  		
  		this_mon.exit();
  	}
  }
    
  
  /** A simple timer task to recalculate all seeding ranks.
   */
  private class RecalcSeedingRanksTask extends TimerTask 
  {
    public void run() {
      // System.out.println("RecalcAllSeedingRanks");
      recalcAllSeedingRanks(false);
    }
  }

  /** This class check if the somethingChanged flag and call process() when
   * its set.  This allows pooling of changes, thus cutting down on the number
   * of sucessive process() calls.
   */
  private class ChangeFlagCheckerTask extends TimerTask 
  {
  	long	last_process_time = 0;
	
    public void run() {
      if (closingDown)
        return;

      long	now = SystemTime.getCurrentTime();
      
      if ( 	now < last_process_time  ||
      		now - last_process_time >= FORCE_CHECK_PERIOD ){
      	
      	somethingChanged	= true;
      }
      		
      if (somethingChanged) {
      	
        try {
        	last_process_time	= now;
        	
        	process();
        	
        } catch( Exception e ) {
        	
        	Debug.printStackTrace( e );
        }
      }
    }
  }
  
  /** Listen to Download changes and recalc SR if needed 
   */
  private class StartStopDownloadListener implements DownloadListener
  {
    public void stateChanged(Download download, int old_state, int new_state) {
      downloadData dlData = (downloadData)downloadDataMap.get(download);

      if (dlData != null) {
        // force a SR recalc, so that it gets positiong properly next process()
        dlData.recalcSeedingRank();
        somethingChanged = true;
        if (bDebugLog) 
        	log.log(dlData.dl.getTorrent(), LoggerChannel.LT_INFORMATION,
							"somethingChanged: stateChange from " + sStates.charAt(old_state)
									+ " (" + old_state + ") to " + sStates.charAt(new_state)
									+ " (" + new_state + ")");
      }
      
      // We have to stop a second torrent from "preparing" at stateChanged,
      // before it has a chance to make a piece check queue (which would
      // run even if we stop it)
      // Checking and stopping in process() would queue the torrent, but
      // there's a good chance all the pieces are queued for recheck.
      if (new_state == Download.ST_PREPARING) {
      	int numPreparing = 0;

        Download[]  downloads = download_manager.getDownloads(false);
        for (int i=0;i<downloads.length;i++){
          Download  dl = downloads[i];
          if (dl.getState() == Download.ST_PREPARING) {
          	numPreparing++;
          	if (numPreparing > 1) {
          		try {
          			download.stopAndQueue();
          		} catch (Exception ignore) {/*ignore*/}
          		break;
          	}
          }
        }
      }
    }

    public void positionChanged(Download download, 
                                int oldPosition, int newPosition) {
      downloadData dlData = (downloadData)downloadDataMap.get(download);
      if (dlData != null) {
        dlData.recalcSeedingRank();
        somethingChanged = true;
        if (bDebugLog) 
          log.log(dlData.dl.getTorrent(), LoggerChannel.LT_INFORMATION,
							"somethingChanged: positionChanged from " + oldPosition + " to "
									+ newPosition);
      }
    }
  }

  /** Update SeedingRank when a new scrape result comes in. 
   */
  private class StartStopDMTrackerListener implements DownloadTrackerListener
  {
  	public void scrapeResult( DownloadScrapeResult result ) {
  		Download dl = result.getDownload();

  		// Skip if error (which happens when listener is first added and the
  		// torrent isn't scraped yet)
  		if (result.getResponseType() == DownloadScrapeResult.RT_ERROR) {
        if (bDebugLog)
					log.log(dl.getTorrent(), LoggerChannel.LT_INFORMATION,
							"Ignored somethingChanged: new scrapeResult (RT_ERROR)");
  			return;
 			}

      downloadData dlData = (downloadData)downloadDataMap.get(dl);
      if (dlData != null) {
        dlData.recalcSeedingRank();
        somethingChanged = true;
        if (bDebugLog)
					log.log(dl.getTorrent(), LoggerChannel.LT_INFORMATION,
							"somethingChanged: new scrapeResult S:" + result.getSeedCount()
									+ ";P:" + result.getNonSeedCount());
      }
  	}
  	
  	public void announceResult( DownloadAnnounceResult result ) {
  		// Announces are useless to us.  Even if the announce contains seed/peer
  		// count, they are not stored in the DownloadAnnounceResult.  Instead,
  		// they are passed off to the DownloadScrapeResult, and a scrapeResult
  		// is triggered
  	}
  }

  /* Create/Remove downloadData object when download gets added/removed.
   * RecalcSeedingRank & process if necessary.
   */
  private class StartStopDMListener implements DownloadManagerListener
  {
    private DownloadTrackerListener download_tracker_listener;
    private DownloadListener        download_listener;
    
    public StartStopDMListener() {
      download_tracker_listener = new StartStopDMTrackerListener();
      download_listener = new StartStopDownloadListener();
    }

    public void downloadAdded( Download  download )
    {
      downloadData dlData = null;
      if (downloadDataMap.containsKey(download)) {
        dlData = (downloadData)downloadDataMap.get(download);
      } else {
        dlData = new downloadData(download);
        downloadDataMap.put( download, dlData );
        download.addListener( download_listener );
        download.addTrackerListener( download_tracker_listener );
      }

      if (dlData != null) {
        dlData.recalcSeedingRank();
        somethingChanged = true;
        if (bDebugLog) 
          log.log(download.getTorrent(), LoggerChannel.LT_INFORMATION,
                  "somethingChanged: downloadAdded");
      }
    }

    public void downloadRemoved( Download  download )
    {
      download.removeListener( download_listener );
      download.removeTrackerListener( download_tracker_listener );

      if (downloadDataMap.containsKey(download)) {
        downloadDataMap.remove(download);
      }

      somethingChanged = true;
      if (bDebugLog) 
        log.log(download.getTorrent(), LoggerChannel.LT_INFORMATION,
                "somethingChanged: downloadRemoved");
    }
  }
  
  private class ChangeCheckerTimerTask extends TimerTask {
    public void run() {
      // make sure process isn't running and stop it from running while we do stuff
      try{
      	this_mon.enter();
      	
        downloadData[] dlDataArray = 
          (downloadData[])downloadDataMap.values().toArray(new downloadData[0]);

        int iNumDLing = 0;
        int iNumCDing = 0;
        for (int i = 0; i < dlDataArray.length; i++) {
          Download dl = dlDataArray[i].getDownloadObject();
          DownloadStats stats = dl.getStats();
          
          // Check DLs for change in activeness (speed threshold)
          // (The call sets somethingChanged it was changed)
          if (dlDataArray[i].getActivelyDownloading())
            iNumDLing++;
          
          // Check Seeders for change in activeness (speed threshold)
          // (The call sets somethingChanged it was changed)
          if (dlDataArray[i].getActivelySeeding()) {
            iNumCDing++;

            int shareRatio = dl.getStats().getShareRatio();
            int numSeeds = calcSeedsNoUs(dl);

            if (iIgnoreShareRatio != 0 && 
                shareRatio > iIgnoreShareRatio && 
                numSeeds >= iIgnoreShareRatio_SeedStart &&
                shareRatio != -1)
              somethingChanged = true;
          }
          
          /* READY downloads are usually waiting for a seeding torrent to
             stop (the seeding torrent probably is within the "Minimum Seeding
             Time" setting)
           */
          if (dl.getState() == Download.ST_READY) {
            somethingChanged = true;
              if (bDebugLog) 
                log.log(dl.getTorrent(), LoggerChannel.LT_INFORMATION,
                        "somethingChanged: Download is ready");
          }
        }

        int iMaxSeeders = calcMaxSeeders(iNumDLing);
        if (iNumCDing > iMaxSeeders) {
          somethingChanged = true;
            if (bDebugLog) 
              log.log(LoggerChannel.LT_INFORMATION,
                      "somethingChanged: More Seeding than limit");
        }
      }finally{
      	
      	this_mon.exit();
      }
    }
  }

  // ConfigurationListener
  public void configurationSaved() {
    reloadConfigParams();
  }

  private void reloadConfigParams() {
  	try{
  		this_mon.enter();
  	
	    // int iOldIgnoreShareRatio = iIgnoreShareRatio;
	    int iNewRankType = plugin_config.getIntParameter("StartStopManager_iRankType");
	    minPeersToBoostNoSeeds = plugin_config.getIntParameter("StartStopManager_iMinPeersToBoostNoSeeds");
	    minSpeedForActiveDL = plugin_config.getIntParameter("StartStopManager_iMinSpeedForActiveDL");
	    minSpeedForActiveSeeding = plugin_config.getIntParameter("StartStopManager_iMinSpeedForActiveSeeding");
	    _maxActive = plugin_config.getIntParameter("max active torrents");
	    _maxActiveWhenSeedingEnabled = plugin_config.getBooleanParameter("StartStopManager_bMaxActiveTorrentsWhenSeedingEnabled");
	    _maxActiveWhenSeeding		= plugin_config.getIntParameter("StartStopManager_iMaxActiveTorrentsWhenSeeding");
	    
	    maxDownloads = plugin_config.getIntParameter("max downloads");
	    numPeersAsFullCopy = plugin_config.getIntParameter("StartStopManager_iNumPeersAsFullCopy");
	    iFakeFullCopySeedStart = plugin_config.getIntParameter("StartStopManager_iFakeFullCopySeedStart");
	    iRankTypeSeedFallback = plugin_config.getIntParameter("StartStopManager_iRankTypeSeedFallback");
	    bAutoReposition = plugin_config.getBooleanParameter("StartStopManager_bAutoReposition");
	    minTimeAlive = plugin_config.getIntParameter("StartStopManager_iMinSeedingTime") * 1000;
	    bPreferLargerSwarms = plugin_config.getBooleanParameter("StartStopManager_bPreferLargerSwarms");
	    bDebugLog = plugin_config.getBooleanParameter("StartStopManager_bDebugLog");
	
	    // Ignore torrent if seed count is at least..
	    iIgnoreSeedCount = plugin_config.getIntParameter("StartStopManager_iIgnoreSeedCount");
	    bIgnore0Peers = plugin_config.getBooleanParameter("StartStopManager_bIgnore0Peers");
	    iIgnoreShareRatio = (int)(1000 * plugin_config.getFloatParameter("Stop Ratio"));
	    iIgnoreShareRatio_SeedStart = plugin_config.getIntParameter("StartStopManager_iIgnoreShareRatioSeedStart");
	    iIgnoreRatioPeers = plugin_config.getIntParameter("Stop Peers Ratio", 0);
	    iIgnoreRatioPeers_SeedStart = plugin_config.getIntParameter("StartStopManager_iIgnoreRatioPeersSeedStart", 0);
	
	    minQueueingShareRatio = plugin_config.getIntParameter("StartStopManager_iFirstPriority_ShareRatio");
	    iFirstPriorityType = plugin_config.getIntParameter("StartStopManager_iFirstPriority_Type");
	    iFirstPrioritySeedingMinutes = plugin_config.getIntParameter("StartStopManager_iFirstPriority_SeedingMinutes");
	    iFirstPriorityDLMinutes = plugin_config.getIntParameter("StartStopManager_iFirstPriority_DLMinutes");
		// Ignore FP
		iFirstPriorityIgnoreSPRatio = plugin_config.getIntParameter("StartStopManager_iFirstPriority_ignoreSPRatio");
		bFirstPriorityIgnore0Peer = plugin_config.getBooleanParameter("StartStopManager_bFirstPriority_ignore0Peer");
	    
	    bAutoStart0Peers = plugin_config.getBooleanParameter("StartStopManager_bAutoStart0Peers");
	    iMaxUploadSpeed = plugin_config.getIntParameter("Max Upload Speed KBs",0);
	
	    boolean	move_top = plugin_config.getBooleanParameter( "StartStopManager_bNewSeedsMoveTop" );
	    plugin_config.setBooleanParameter( PluginConfig.CORE_PARAM_BOOLEAN_NEW_SEEDS_START_AT_TOP, move_top );
	    
	    if (iNewRankType != iRankType) {
	      iRankType = iNewRankType;
	      
	      // shorted recalc for timed rank type, since the calculation is fast and we want to stop on the second
	      if (iRankType == RANK_TIMED) {
	        if (recalcSeedingRanksTask == null) {
	          recalcSeedingRanksTask = new RecalcSeedingRanksTask();
	          changeCheckerTimer.schedule(recalcSeedingRanksTask, 1000, 1000);
	        }
	      } else if (recalcSeedingRanksTask != null) {
	        recalcSeedingRanksTask.cancel();
	        recalcSeedingRanksTask = null;
	      }
	    }
	    recalcAllSeedingRanks(true);
	    somethingChanged = true;
	    if (bDebugLog) {
	      log.log(LoggerChannel.LT_INFORMATION,
	              "somethingChanged: config reload");
	      try {
	        if (debugMenuItem == null ){
	        	
	        	debugMenuItem = seedingRankColumn.addContextMenuItem("StartStopRules.menu.viewDebug");
	        	
	        	debugMenuItem.addListener(new MenuItemListener() {
	        		public void 
	        		selected(MenuItem _menu, Object _target) 
	        		{
	        			Download dl = (Download)((TableRow)_target).getDataSource();
	        			
	        			downloadData dlData = (downloadData)downloadDataMap.get(dl);
	  
	        			if ( dlData != null ){
	        				plugin_interface.getUIManager().showTextMessage(null, null, dlData.sExplainFP + "\n" + dlData.sExplainSR + dlData.sTrace);
	        			}
	        		}
	        	});
	        }
	      } catch (Throwable t) { 
	    	  Debug.printStackTrace( t );
	      }
	    }
  	}finally{
  		
  		this_mon.exit();
  	}
  }
  
  private int calcMaxSeeders(int iDLs) {
    // XXX put in subtraction logic here
	  int	maxActive = getMaxActive();
    return (maxActive == 0) ? 99999 : maxActive - iDLs;
  }

  protected int
  getMaxActive()
  {	  
	  if ( !_maxActiveWhenSeedingEnabled ){
		  
		  return( _maxActive );
	  }
	 
	  if ( download_manager.isSeedingOnly()){
	
		  if ( _maxActiveWhenSeeding <= _maxActive ){
			  
			  return( _maxActiveWhenSeeding );
		  }
		  
		  	// danger here if we are in a position where allowing more to start when seeding
		  	// allows a non-seeding download to start (looping occurs)
		  
		  Download[]	downloads = download_manager.getDownloads();
		  
		  boolean	danger = false;
		  
		  for (int i=0;i<downloads.length;i++){
			  
			  Download	download	= downloads[i];
			  
			  int	state = download.getState();
			  
			  if (	state == Download.ST_DOWNLOADING ||
					state == Download.ST_SEEDING ||
					state == Download.ST_STOPPED ||
					state == Download.ST_STOPPING ||
					state == Download.ST_ERROR ){
				  
				  	// not interesting, they can't potentially cause trouble
				  
			  }else{
				  
				  	// look for incomplete files
				  
				  DiskManagerFileInfo[]	files = download.getDiskManagerFileInfo();
				  
				  for (int j=0;j<files.length;j++){
					  
					  DiskManagerFileInfo	file = files[j];
					  
					  if ( 	(!file.isSkipped()) &&
							  file.getDownloaded() != file.getLength()){
						  
						  danger	= true;
						  
						  break;
					  }
				  } 
			  }  
			  
			  if ( danger ){
				  
				  break;
			  }
		  }
		  
		  if ( danger ){
			  
			  return( _maxActive );
		  }
		  
		  return( _maxActiveWhenSeeding );
	  }else{
		  
		  return( _maxActive );
	  }
  }
  
  protected void process() {
  	try{
  		this_mon.enter();
  	
	    // long  process_time = SystemTime.getCurrentTime();
	
	    // total Forced Seeding doesn't include stalled torrents
	    int totalForcedSeeding = 0;
	    int totalForcedSeedingNonFP = 0;
	    int totalWaitingToSeed = 0;
	    int totalWaitingToDL = 0;
	    int totalDownloading = 0;
	    int activeDLCount = 0;
	    int activeSeedingCount = 0;
	    int totalComplete = 0;
	    int totalIncompleteQueued = 0;
	    int totalFirstPriority = 0;
	    int totalStalledSeeders = 0;
	    int totalFPStalledSeeders = 0;
	    int total0PeerSeeders = 0;
	    int totalAllocatingOrChecking = 0;
	    
	    boolean bDebugOn = false;
	
	    // pull the data into a local array, so we don't have to lock/synchronize
	    downloadData[] dlDataArray;
	    dlDataArray = (downloadData[])
	      downloadDataMap.values().toArray(new downloadData[downloadDataMap.size()]);
	
	    // Start seeding right away if there's no auto-ranking
	    // Otherwise, wait a maximium of 90 seconds for scrape results to come in
	    // When the first scrape result comes in, bHasNonFPRank will turn to true
	    // (see logic in 1st loop)
	    boolean bHasNonFPRank = (iRankType == RANK_NONE)
					|| (iRankType == RANK_TIMED)
					|| (SystemTime.getCurrentTime() - startedOn > MIN_FIRST_SCRAPE_WAIT);
	
	    // Loop 1 of 2:
	    // - Build a SeedingRank list for sorting
	    // - Build Count Totals
	    // - Do anything that doesn't need to be done in Queued order
	    for (int i = 0; i < dlDataArray.length; i++) {
	      downloadData dlData = dlDataArray[i];
	      
	      Download download = dlData.getDownloadObject();
	      DownloadStats stats = download.getStats();
	      int completionLevel = stats.getDownloadCompleted(false);
	      boolean bIsFirstP = false;
	
	      // Count forced seedings as using a slot
	      // Don't count forced downloading as using a slot
	      if (completionLevel < 1000 && download.isForceStart())
	        continue;
	
	      int state = download.getState();
	
	      if (completionLevel == 1000) {
	        if (!bHasNonFPRank && 
	            (download.getSeedingRank() > 0) && 
	            (state == Download.ST_QUEUED ||
	             state == Download.ST_READY)
	             && (SystemTime.getCurrentTime() - startedOn > MIN_SEEDING_STARTUP_WAIT)) {
	          bHasNonFPRank = true;
	        }
	
	        if (state != Download.ST_ERROR && state != Download.ST_STOPPED) {
	          totalComplete++;
	          
	          if (dlData.isFirstPriority()) {
		            totalFirstPriority++;
		            bIsFirstP = true;
		      }
	          
	          if (dlData.getActivelySeeding()) {
	            activeSeedingCount++;
	            if (download.isForceStart()) {
	              totalForcedSeeding++;
	              if (!bIsFirstP) totalForcedSeedingNonFP++;
	            }
	          } else if (state == Download.ST_SEEDING) {
	        	  if (bIsFirstP) {
	        		  totalFPStalledSeeders++;
	        	  }
	        	  
	            totalStalledSeeders++;
	            if (bAutoStart0Peers && calcPeersNoUs(download) == 0 && scrapeResultOk(download))
	            	total0PeerSeeders++;
	          }
	          if (state == Download.ST_READY ||
	              state == Download.ST_WAITING ||
	              state == Download.ST_PREPARING) {
	            totalWaitingToSeed++;
	          }
	  
	        }
	      } else {
	        if (state == Download.ST_DOWNLOADING)
	          totalDownloading++;
	        if (dlData.getActivelyDownloading())
	          activeDLCount++;
	
	        if (state == Download.ST_READY ||
	            state == Download.ST_WAITING ||
	            state == Download.ST_PREPARING) {
	          totalWaitingToDL++;
	        } else if (state == Download.ST_QUEUED) {
	          totalIncompleteQueued++;
	        }
	      }

	      if (state == Download.ST_PREPARING)
	      	totalAllocatingOrChecking++;
	    }
	    
	    int maxSeeders = calcMaxSeeders(activeDLCount + totalWaitingToDL);
	    
	    int	maxActive = getMaxActive();
	    
	    int maxTorrents;
	    if (maxActive == 0) {
	      maxTorrents = 9999;
	    } else if (iMaxUploadSpeed == 0) {
	      maxTorrents = maxActive + 4;
	    } else {
	      // Don't allow more "seeding/downloading" torrents than there is enough
	      // bandwidth for.  There needs to be enough bandwidth for at least
	      // each torrent to get to its minSpeedForActiveSeeding  
	      // (we buffer it at 2x just to be safe).
	      int minSpeedPerActive = (minSpeedForActiveSeeding * 2) / 1024;
	      // Even more buffering/limiting.  Limit to enough bandwidth for
	      // each torrent to have potentially 3kbps.
	      if (minSpeedPerActive < 3)
	        minSpeedPerActive = 3;
	      maxTorrents = (iMaxUploadSpeed / minSpeedPerActive);
	      // Allow user to do stupid things like have more slots than their 
	      // upload speed can handle
	      if (maxTorrents < maxActive)
	        maxTorrents = maxActive;
	      //System.out.println("maxTorrents = " + maxTorrents + " = " + iMaxUploadSpeed + " / " + minSpeedPerActive);
	      //System.out.println("totalTorrents = " + (activeSeedingCount + totalStalledSeeders + totalDownloading));
	    }
	
	    String[] mainDebugEntries = null;
	    if (bDebugLog) {
	      log.log(LoggerChannel.LT_INFORMATION, ">>process()");
	      mainDebugEntries = new String[] { 
	              "bHasSR="+bHasNonFPRank,
	              "tFrcdCding="+totalForcedSeeding,
	              "actvCDs="+activeSeedingCount,
	              "tW8tingToCd="+totalWaitingToSeed,
	              "tDLing="+totalDownloading,
	              "actvDLs="+activeDLCount,
	              "tW8tingToDL="+totalWaitingToDL,
	              "tCom="+totalComplete,
	              "tIncQd="+totalIncompleteQueued,
	              "mxCdrs="+maxSeeders,
	              "t1stPr="+totalFirstPriority,
	              "maxT="+maxTorrents
	                      };
	    }
	
	  	somethingChanged = false;
	
	    // Sort by SeedingRank
	    if (iRankType != RANK_NONE)
	      Arrays.sort(dlDataArray);
	    else
	      Arrays.sort(dlDataArray, new Comparator () {
	        public final int compare (Object a, Object b) {
	          Download aDL = ((downloadData)a).getDownloadObject();
	          Download bDL = ((downloadData)b).getDownloadObject();
	          boolean aIsComplete = aDL.getStats().getDownloadCompleted(false) == 1000;
	          boolean bIsComplete = bDL.getStats().getDownloadCompleted(false) == 1000;
	          if (aIsComplete && !bIsComplete)
	            return 1;
	          if (!aIsComplete && bIsComplete)
	            return -1;
	          boolean aIsFP = ((downloadData)a).isFirstPriority();
	          boolean bIsFP = ((downloadData)b).isFirstPriority();
	          if (aIsFP && !bIsFP)
	            return -1;
	          if (!aIsFP && bIsFP)
	            return 1;
	          return aDL.getPosition() - bDL.getPosition();
	        }
	      } );
	
	    // pre-included Forced Start torrents so a torrent "above" it doesn't 
	    // start (since normally it would start and assume the torrent below it
	    // would stop)
	    int numWaitingOrSeeding = totalForcedSeeding; // Running Count
	    int numWaitingOrDLing = 0;   // Running Count
	    int numPreparing = 0; // Running Count
	    /**
	     * store whether there's a torrent higher in the list that is queued
	     * We don't want to start a torrent lower in the list if there's a higherQueued
	     */
	    boolean higherQueued = false;
	    /**
	     * Tracks the position we should be at in the Completed torrents list
	     * Updates position.
	     */
	    int posComplete = 0;
	
	    // Loop 2 of 2:
	    // - Start/Stop torrents based on criteria
	    
	    	// find the smallest entry ready to be initialised so that we hash-check
	    	// the smallest files first
	    
	    long	smallest_size		= 0x7fffffffffffffffL;
	    int		smallest_size_index = -1;
	    
	    for (int i = 0; i < dlDataArray.length; i++) {
	    	
	    	downloadData dlData = dlDataArray[i];
		    Download download = dlData.getDownloadObject();
			      
		    if (	download.getState() == Download.ST_WAITING &&
		      			( 	download.getStats().getDownloadCompleted(false) == 1000	||	// PARG - kick off all seeders straight away
		      																			// as we don't want time-consuming rechecking 
																						// to hold up seeding
		      					totalAllocatingOrChecking == 0)) {
		    
		    	Torrent	t = download.getTorrent();
		    	
		    	if ( t == null ){
		    	
		    			// broken torrent, set index in case nothing else matches
		    		
		    		smallest_size_index	= i;
		    		
		    	}else{
		    		long	size = t.getSize();
		    		
		    		if ( size < smallest_size ){
		    			
		    			smallest_size	= size;
		    			
		    			smallest_size_index	= i;
		    		}
		    	}
		    }
	    }	    
	    
	    for (int i = 0; i < dlDataArray.length; i++) {
	      downloadData dlData = dlDataArray[i];
	      Download download = dlData.getDownloadObject();
	      boolean bStopAndQueued = false;
	      dlData.sTrace = "";
	
	      // Initialize STATE_WAITING torrents

	      if ( i == smallest_size_index ){
	      	try{
	          download.initialize();
	        }catch (Exception ignore) {/*ignore*/}
	      }
	
	      if (bAutoReposition &&
	          (iRankType != RANK_NONE) &&
	          download.getStats().getDownloadCompleted(false) == 1000 &&
	          (bHasNonFPRank || totalFirstPriority > 0))
	        download.setPosition(++posComplete);
	
        int state = download.getState();
        
	      // Never do anything to stopped entries
	      if (state == Download.ST_STOPPING ||
	      		state == Download.ST_STOPPED ||
	      		state == Download.ST_ERROR) {
	        continue;
	      }
	      
	      if (state == Download.ST_PREPARING)
	      	numPreparing++;
	
	      // Handle incomplete DLs
	      if (download.getStats().getDownloadCompleted(false) != 1000) {
	        if (bDebugLog) {
	          String s = ">> state="+sStates.charAt(download.getState())+
	                  ";shareRatio="+download.getStats().getShareRatio()+
	                  ";numW8tngorDLing="+numWaitingOrDLing+
	                  ";maxCDrs="+maxSeeders+
	                  ";forced="+download.isForceStart()+
	                  ";forcedStart="+download.isForceStart()+
	                  ";actvDLs="+activeDLCount+
	                  "";
	          log.log(download.getTorrent(), LoggerChannel.LT_INFORMATION, s);
	          dlData.sTrace += s + "\n";
	        }
	
	        if (download.isForceStart())
	          continue;
	          
	        int maxDLs = 0;
	        int DLmax = 0;
	        if (maxActive == 0) {
	        	maxDLs = maxDownloads;
	        } else {
		        DLmax =  totalFPStalledSeeders + maxActive - totalFirstPriority - totalForcedSeedingNonFP;
		        maxDLs = ( DLmax <= 0 ) ? 0 : maxDownloads - DLmax <= 0 ? maxDownloads :  DLmax;
	        }
	        
	        if (bDebugOn) {
		        System.out.println( "maxActive: " + maxActive + " / activeSeedingCount: " + activeSeedingCount + " / maxDownloads: " + maxDownloads + " / maxDLs: " + maxDLs + " / DLmax: " + DLmax);
		        System.out.println("totalFirstPriority: " + totalFirstPriority + " / totalFPStalledSeeders: " + totalFPStalledSeeders + " / total0PeerSeeders: "  + total0PeerSeeders);	        	
	        }
	        
	        if (state == Download.ST_PREPARING) {
	          // Don't mess with preparing torrents.  they could be in the 
	          // middle of resume-data building, or file allocating.
	          numWaitingOrDLing++;
	
	        } else if ( state == Download.ST_READY ||
	        		   state == Download.ST_DOWNLOADING ||
	                   state == Download.ST_WAITING) {
	
	          boolean bActivelyDownloading = dlData.getActivelyDownloading();
	          
		      if (bDebugOn) {
		    	  System.out.println("D : " + dlData.getDownloadObject().getName());
		    	  System.out.println( "Before: numWaitingOrDLing: " + numWaitingOrDLing + " / " + "activeDLCount: " + activeDLCount);
		      }
		      
	          // Stop torrent if over limit
	          if ((maxDownloads != 0) &&
	              (numWaitingOrDLing >= maxDLs) &&
	              ((bActivelyDownloading || state != Download.ST_DOWNLOADING) || (state == Download.ST_DOWNLOADING && maxActive != 0 && !bActivelyDownloading && activeSeedingCount + activeDLCount >= maxActive))) {
	            try {
	              if (bDebugLog) {
	                String s = "   stopAndQueue() > maxDownloads";
	                log.log(download.getTorrent(), LoggerChannel.LT_INFORMATION, s);
	                dlData.sTrace += s + "\n";
	              }
	              download.stopAndQueue();
	              // reduce counts
	              if (state == Download.ST_DOWNLOADING) {
	                totalDownloading--;
	                if (bActivelyDownloading)
	                  activeDLCount--;
	              } else {
	                totalWaitingToDL--;
	              }
	              maxSeeders = calcMaxSeeders(activeDLCount + totalWaitingToDL);
	            } catch (Exception ignore) {/*ignore*/}
	            
	            state = download.getState();
	          } else if ( state == Download.ST_DOWNLOADING && bActivelyDownloading || state == Download.ST_READY) {
	            numWaitingOrDLing++;
	          }
	        }
	

	
	        if (state == Download.ST_READY) {
	          if ((maxDownloads == 0) || (activeDLCount < maxDLs)) {
	            try {
	              if (bDebugLog) {
	                String s = "   start() activeDLCount < maxDownloads";
	                log.log(download.getTorrent(), LoggerChannel.LT_INFORMATION, s);
	                dlData.sTrace += s + "\n";
	              }
	              download.start();
	
	              // adjust counts
	              totalWaitingToDL--;
	              activeDLCount++;
	              //numWaitingOrDLing++;
	              maxSeeders = calcMaxSeeders(activeDLCount + totalWaitingToDL);
	            } catch (Exception ignore) {/*ignore*/}
	            state = download.getState();
	          }
	        }
	        
	        if (state == Download.ST_QUEUED) { 
		          if ((maxDownloads == 0) || (numWaitingOrDLing < maxDLs)) {
		            try {
		              if (bDebugLog) {
		                String s = "   restart()";
		                log.log(LoggerChannel.LT_INFORMATION, s);
		                dlData.sTrace += s + "\n";
		              }
		              download.restart();
		
		              // increase counts
		              totalWaitingToDL++;
		              numWaitingOrDLing++;
		              maxSeeders = calcMaxSeeders(activeDLCount + totalWaitingToDL);
		            } catch (Exception ignore) {/*ignore*/}
		            state = download.getState();
		          }
		        }
	        
	        if (bDebugOn) {
	        	System.out.println( "After: numWaitingOrDLing: " + numWaitingOrDLing + " / " + "activeDLCount: " + activeDLCount);
	        }
	        if (bDebugLog) {
	          String s = "<< state="+sStates.charAt(download.getState())+
	                  ";shareRatio="+download.getStats().getShareRatio()+
	                  ";numW8tngorDLing="+numWaitingOrDLing+
	                  ";maxCDrs="+maxSeeders+
	                  ";forced="+download.isForceStart()+
	                  ";forcedStart="+download.isForceStart()+
	                  ";actvDLs="+activeDLCount+
	                  "";
	          log.log(download.getTorrent(), LoggerChannel.LT_INFORMATION, s);
	          dlData.sTrace += s + "\n";
	        }
	      }
	      else if (bHasNonFPRank || totalFirstPriority > 0) { // completed
	        String[] debugEntries = null;
	        String sDebugLine = "";
	        // Queuing process:
	        // 1) Torrent is Queued (Stopped)
	        // 2) Slot becomes available
	        // 3) Queued Torrent changes to Waiting
	        // 4) Waiting Torrent changes to Ready
	        // 5) Ready torrent changes to Seeding (with startDownload)
	        // 6) Trigger stops Seeding torrent
	        //    a) Queue Ranking drops
	        //    b) User pressed stop
	        //    c) other
	        // 7) Seeding Torrent changes to Queued.  Go to step 1.
	        
	        boolean isFP = dlData.isFirstPriority();
	        if (!bHasNonFPRank && !isFP)
	          continue;
	
	        int numPeers = calcPeersNoUs(download);
	        
	        // Ignore rules and other auto-starting rules do not apply when 
	        // bAutoStart0Peers and peers == 0. So, handle starting 0 peers 
	        // right at the beginning, and loop early
	        if (totalAllocatingOrChecking == 0 && bAutoStart0Peers
							&& numPeers == 0 && scrapeResultOk(download)) {
	          if (state == Download.ST_QUEUED) {
	            try {
	              if (bDebugLog)
	                sDebugLine += "\nrestart() 0Peers";
	              download.restart(); // set to Waiting
		            totalWaitingToSeed++;
		            numWaitingOrSeeding++;

	              state = download.getState();
	              if (state == Download.ST_READY) {
	                if (bDebugLog)
	                  sDebugLine += "\nstart(); 0Peers";
	                download.start();
			            activeSeedingCount++;
	              }
	            } catch (Exception ignore) {/*ignore*/}
	          }
	          if (state == Download.ST_READY) {
	            try {
	              if (bDebugLog)
	                sDebugLine += "\nstart(); 0Peers";
	              download.start();
		            activeSeedingCount++;
		            numWaitingOrSeeding++;
	            } catch (Exception ignore) {/*ignore*/}
	          }
	          continue;
	        }
			
	        if (bDebugOn) {
	        	System.out.println("S : " + dlData.getDownloadObject().getName());
	        	System.out.println("numWaitingOrSeeding " + numWaitingOrSeeding + " / " + "maxSeeders " + maxSeeders + " / " + "maxActive " + maxActive);
	        }

			
	        int shareRatio = download.getStats().getShareRatio();
	        boolean bActivelySeeding = dlData.getActivelySeeding();
	        boolean okToQueue = (state == Download.ST_READY || state == Download.ST_SEEDING) &&
								(!isFP || (isFP && (( maxActive != 0 && numWaitingOrSeeding >= maxSeeders)  )) ) &&
	//							(!isFP || (isFP && ((numWaitingOrSeeding >= maxSeeders) || (!bActivelySeeding && (numWaitingOrSeeding + totalStalledSeeders) >= maxSeeders))) ) &&
								(!download.isForceStart());

	        // in RANK_TIMED mode, we use minTimeAlive for rotation time, so
	        // skip check
			// XXX do we want changes to take effect immediately  ?
	        if (okToQueue && (state == Download.ST_SEEDING) && iRankType != RANK_TIMED) {
	          long timeAlive = (SystemTime.getCurrentTime() - download.getStats().getTimeStarted());
	          okToQueue = (timeAlive >= minTimeAlive);
	        }
	        
	        if (state != Download.ST_QUEUED &&  // Short circuit.
	            (state == Download.ST_READY ||
	             state == Download.ST_WAITING ||
	             state == Download.ST_PREPARING ||
	             // Forced Start torrents are pre-included in count
	             (state == Download.ST_SEEDING && bActivelySeeding && !download.isForceStart())
	            )) {
	          numWaitingOrSeeding++;
	        }
	
	        if (bDebugLog) {
	          debugEntries = new String[] { "state="+sStates.charAt(state),
	                           "shareR="+shareRatio,
	                           "nWorCDing="+numWaitingOrSeeding,
	                           "nWorDLing="+numWaitingOrDLing,
	                           "ok2Q="+okToQueue,
	                           "sr="+download.getSeedingRank(),
	                           "hgherQd="+higherQueued,
	                           "maxCDrs="+maxSeeders,
	                           "1stP="+isFP,
	                           "actCDingCount="+activeSeedingCount,
	                           "ActCDing="+bActivelySeeding
	                          };
	        }
	        
	        // Note: First Priority are sorted to the top, 
	        //       so they will always start first
	        
	        // Process some ignore rules
	        // If torrent maches criteria, the SeedingRank will be <= -2 and will be
	        // stopped later in the code
	        if (okToQueue) {
	          int numSeeds = calcSeedsNoUs(download);
	          // ignore when Share Ratio reaches # in config
	          //0 means unlimited
	          if (iIgnoreShareRatio != 0 && 
	              shareRatio > iIgnoreShareRatio && 
	              numSeeds >= iIgnoreShareRatio_SeedStart &&
	              shareRatio != -1 &&
	              download.getSeedingRank() != SR_SHARERATIOMET) 
	          {
	            if (bDebugLog)
	              sDebugLine += "\nShare Ratio Met";
	            download.setSeedingRank(SR_SHARERATIOMET);
	          }
	  
	          // Ignore when P:S ratio met
	          if (iIgnoreRatioPeers != 0 && 
	              download.getSeedingRank() != SR_RATIOMET) 
	          {
	            //If there are no seeds, avoid / by 0
	            if (numSeeds != 0 && numSeeds >= iIgnoreRatioPeers_SeedStart) {
	              float ratio = (float) numPeers / numSeeds;
	              if (ratio <= iIgnoreRatioPeers) {
	                sDebugLine += "\nP:S Met";
	                download.setSeedingRank(SR_RATIOMET);
	              }
	            }
	          }
	
	        // XXX Change to waiting if queued and we have an open slot
	        } else if ((state == Download.ST_QUEUED) &&
	                   (maxActive == 0 || numWaitingOrSeeding < maxSeeders) && 
//	                   (maxActive == 0 || (activeSeedingCount + activeDLCount) < maxActive) &&
	                   (download.getSeedingRank() > -2) && 
	                   !higherQueued && totalAllocatingOrChecking == 0) {
	          try {
	            if (bDebugLog)
	              sDebugLine += "\nrestart() numWaitingOrSeeding < maxSeeders";
	            download.restart(); // set to Waiting
	            okToQueue = false;
	            totalWaitingToSeed++;
	            numWaitingOrSeeding++;
	            if (iRankType == RANK_TIMED)
	              dlData.recalcSeedingRank();
	          } catch (Exception ignore) {/*ignore*/}
	          state = download.getState();
	        }
	        
	        // Start download if ready and slot is available
	        if (state == Download.ST_READY && activeSeedingCount < maxSeeders) {

	          if (download.getSeedingRank() > -2 || download.isForceStart()) {
	          	if (totalAllocatingOrChecking == 0) {
		            try {
		              if (bDebugLog)
		                sDebugLine += "\nstart(); activeSeedingCount < maxSeeders";
		              download.start();
		              okToQueue = false;
		            } catch (Exception ignore) {/*ignore*/}
		            state = download.getState();
		            activeSeedingCount++;
		            numWaitingOrSeeding++;
	          	}
	          } else if (okToQueue) {
	            // In between switching from STATE_WAITING and STATE_READY,
	            // and ignore rule was met, so move it back to Queued
	            try {
	              if (bDebugLog)
	                sDebugLine += "\nstopAndQueue()";
	              download.stopAndQueue();
	              bStopAndQueued = true;
	              totalWaitingToSeed--;
	              if (bActivelySeeding)
	                numWaitingOrSeeding--;
	            } catch (Exception ignore) {/*ignore*/}
	            state = download.getState();
	          }
	        }
	
	        // if there's more torrents waiting/seeding than our max, or if
	        // there's a higher ranked torrent queued, stop this one
			// XXX Gouss - stop non active torrents if lower in the queue than 
			// the last seeding torrent
	        if (okToQueue &&
	            (((bActivelySeeding || state != Download.ST_SEEDING) &&
	            ((numWaitingOrSeeding > maxSeeders) || 
	            (numWaitingOrSeeding >= maxSeeders && higherQueued) ||
	             download.getSeedingRank() <= -2)) ||
	             ((!bActivelySeeding && state == Download.ST_SEEDING) &&
				            ((numWaitingOrSeeding >= maxSeeders) || 
				             download.getSeedingRank() <= -2)))) 
	        {
	          try {
	            if (bDebugLog) {
	              sDebugLine += "\nstopAndQueue()";
	              if (numWaitingOrSeeding > maxSeeders)
	                sDebugLine += "; > Max";
	              if (higherQueued)
	                sDebugLine += "; higherQueued (it should be seeding instead of this one)";
	              if (download.getSeedingRank() <= -2)
	                sDebugLine += "; ignoreRule met";
	            }
	
	            if (state == Download.ST_READY)
	              totalWaitingToSeed--;
	
	            download.stopAndQueue();
	            bStopAndQueued = true;
	            // okToQueue only allows READY and SEEDING state.. and in both cases
	            // we have to reduce counts
	            if (bActivelySeeding) {
	              activeSeedingCount--;
	              numWaitingOrSeeding--;
	            }
	          } catch (Exception ignore) {/*ignore*/}
	          state = download.getState();
	        }
	
	        // move completed timed rank types to bottom of the list
	        if (bStopAndQueued && iRankType == RANK_TIMED) {
	          for (int j = 0; j < dlDataArray.length; j++) {
	          	Download dl = dlDataArray[j].getDownloadObject(); 
	            int sr = dl.getSeedingRank();
	            if (sr > 0 && sr < SR_TIMED_QUEUED_ENDS_AT) {
	              // Move everyone up
	              // We always start by setting SR to SR_TIMED_QUEUED_ENDS_AT - position
	              // then, the torrent with the biggest starts seeding which is
	              // (SR_TIMED_QUEUED_ENDS_AT - 1), leaving a gap.
	              // when it's time to stop the torrent, move everyone up, and put 
	              // us at the end
	              dl.setSeedingRank(sr + 1);
	            }
	          }
	          download.setSeedingRank(SR_TIMED_QUEUED_ENDS_AT - totalComplete);
	        }
	
	        if (download.getState() == Download.ST_QUEUED && 
	            download.getSeedingRank() >= 0)
	          higherQueued = true;
	
	        if (bDebugLog) {
	          String[] debugEntries2 = new String[] { "state="+sStates.charAt(download.getState()),
	                           "shareR="+download.getStats().getShareRatio(),
	                           "nWorCDing="+numWaitingOrSeeding,
	                           "nWorDLing="+numWaitingOrDLing,
	                           "ok2Q="+okToQueue,
	                           "sr="+download.getSeedingRank(),
	                           "hgherQd="+higherQueued,
	                           "maxCDrs="+maxSeeders,
	                           "1stP="+dlData.isFirstPriority(),
	                           "actCDingCount="+activeSeedingCount,
	                           "ActCDing="+bActivelySeeding
	                          };
	          printDebugChanges("", debugEntries, debugEntries2, sDebugLine, "  ", true, dlData);
	        }
	
	      } // getDownloadCompleted == 1000
	    } // Loop 2/2 (Start/Stopping)
	    
	    if (bDebugLog) {
	      String[] mainDebugEntries2 = new String[] { 
	          "bHasSR="+bHasNonFPRank,
	          "tFrcdCding="+totalForcedSeeding,
	          "actvCDs="+activeSeedingCount,
	          "tW8tingToCd="+totalWaitingToSeed,
	          "tDLing="+totalDownloading,
	          "actvDLs="+activeDLCount,
	          "tW8tingToDL="+totalWaitingToDL,
	          "tCom="+totalComplete,
	          "tIncQd="+totalIncompleteQueued,
	          "mxCdrs="+maxSeeders,
	          "t1stPr="+totalFirstPriority,
	          "maxT="+maxTorrents
	                  };
	      printDebugChanges("<<process() ", mainDebugEntries, mainDebugEntries2, "", "", true, null);
	    }
  	}finally{
  		
  		this_mon.exit();
  	}
  } // process()
  
  private void printDebugChanges(String sPrefixFirstLine, 
                                 String[] oldEntries, 
                                 String[] newEntries,
                                 String sDebugLine,
                                 String sPrefix, 
                                 boolean bAlwaysPrintNoChangeLine,
                                 downloadData dlData) {
      boolean bAnyChanged = false;
      String sDebugLineNoChange = sPrefixFirstLine;
      String sDebugLineOld = "";
      String sDebugLineNew = "";
      for (int j = 0; j < oldEntries.length; j++) {
        if (oldEntries[j].equals(newEntries[j]))
          sDebugLineNoChange += oldEntries[j] + ";";
        else {
          sDebugLineOld += oldEntries[j] + ";";
          sDebugLineNew += newEntries[j] + ";";
          bAnyChanged = true;
        }
      }
      String sDebugLineOut = ((bAlwaysPrintNoChangeLine || bAnyChanged) ? sDebugLineNoChange : "") +
                             (bAnyChanged ? "\nOld:"+sDebugLineOld+"\nNew:"+sDebugLineNew : "") + 
                             sDebugLine;
      if (!sDebugLineOut.equals("")) {
        String[] lines = sDebugLineOut.split("\n");
        for (int i = 0; i < lines.length; i++) {
          String s = sPrefix + ((i>0)?"  ":"") + lines[i];
          if (dlData == null) {
          	log.log(LoggerChannel.LT_INFORMATION, s);
          } else {
          	log.log(dlData.dl.getTorrent(), LoggerChannel.LT_INFORMATION, s);
          	dlData.sTrace += s + "\n";
          }
        }
      }
  }

  public boolean getAlreadyAllocatingOrChecking() {
    Download[]  downloads = download_manager.getDownloads(false);
    for (int i=0;i<downloads.length;i++){
      Download  download = downloads[i];
      int state = download.getState();
      if (state == Download.ST_PREPARING)
        return true;
    }
    return false;
  }



  /**
   * Get # of peers not including us
   *
   */
  public int calcPeersNoUs(Download download) {
    int numPeers = 0;
    DownloadScrapeResult sr = download.getLastScrapeResult();
    if (sr.getScrapeStartTime() > 0) {
      numPeers = sr.getNonSeedCount();
      // If we've scraped after we started downloading
      // Remove ourselves from count
      if ((numPeers > 0) &&
          (download.getState() == Download.ST_DOWNLOADING) &&
          (sr.getScrapeStartTime() > download.getStats().getTimeStarted()))
        numPeers--;
    }
    if (numPeers == 0) {
    	// Fallback to the # of peers we know of
      DownloadAnnounceResult ar = download.getLastAnnounceResult();
      if (ar != null && ar.getResponseType() == DownloadAnnounceResult.RT_SUCCESS)
        numPeers = ar.getNonSeedCount();
    }
    return numPeers;
  }
  
  private boolean scrapeResultOk(Download download) {
    DownloadScrapeResult sr = download.getLastScrapeResult();
    return (sr.getResponseType() == DownloadScrapeResult.RT_SUCCESS);
  }

  /** Get # of seeds, not including us, AND including fake full copies
   * 
   * @param download Download to get # of seeds for
   * @return seed count
   */
  public int calcSeedsNoUs(Download download) {
  	return calcSeedsNoUs(download, calcPeersNoUs(download));
  }

  /** Get # of seeds, not including us, AND including fake full copies
   * 
   * @param download Download to get # of seeds for
   * @param numPeers # peers we know of, required to calculate Fake Full Copies
   * @return seed count
   */
  public int calcSeedsNoUs(Download download, int numPeers) {
    int numSeeds = 0;
    DownloadScrapeResult sr = download.getLastScrapeResult();
    if (sr.getScrapeStartTime() > 0) {
      long seedingStartedOn = download.getStats().getTimeStartedSeeding();
      numSeeds = sr.getSeedCount();
      // If we've scraped after we started seeding
      // Remove ourselves from count
      if ((numSeeds > 0) &&
          (seedingStartedOn > 0) &&
          (download.getState() == Download.ST_SEEDING) &&
          (sr.getScrapeStartTime() > seedingStartedOn))
        numSeeds--;
    }
    if (numSeeds == 0) {
    	// Fallback to the # of seeds we know of
      DownloadAnnounceResult ar = download.getLastAnnounceResult();
      if (ar != null && ar.getResponseType() == DownloadAnnounceResult.RT_SUCCESS)
        numSeeds = ar.getSeedCount();
    }

	  if (numPeersAsFullCopy != 0 && numSeeds >= iFakeFullCopySeedStart)
      numSeeds += numPeers / numPeersAsFullCopy;

    return numSeeds;
  }


  public class downloadData implements Comparable
  {
  	/** Wait XX ms before really changing activity (DL or CDing) state when
  	 * state changes via speed change
  	 */ 
  	private final int ACTIVE_CHANGE_WAIT = 10000;
  	
  	// Base will be shifted over by 4 digits
		private final int SPRATIO_SHIFT = 10000;
		private final int SPRATIO_BASE_LIMIT = (SR_INCOMPLETE_ENDS_AT - SPRATIO_SHIFT) / SPRATIO_SHIFT - 1;

		// This is actually 1/2 the limit.  Doubling SPRATIO_BASE_LIMIT is 
		// intentional to make gap more visible, and to handle any SR added due to 
		// peers
		private final int SEEDCOUNT_LIMIT1 = SR_INCOMPLETE_ENDS_AT  / 2 - SPRATIO_BASE_LIMIT - 1;
		// Save 3 digits for tie breaking
		private final int SEEDCOUNT_LIMIT2 = SEEDCOUNT_LIMIT1 / 1000;  

  	protected Download dl;
    private boolean bActivelyDownloading;
    private long lDLActivelyChangedOn;
    private boolean bActivelySeeding;
    private long lCDActivelyChangedOn;
    private boolean bIsFirstPriority;
    public String sExplainFP = "";
    public String sExplainSR = "";
    public String sTrace = "";
    
    private AEMonitor		downloadData_this_mon	= new AEMonitor( "StartStopRules:downloadData" );

    
    /** Sort first by SeedingRank Descending, then by Position Ascending.
      */
    public int compareTo(Object obj)
    {
    	downloadData dlData = (downloadData) obj;
			if (dlData.bIsFirstPriority && !bIsFirstPriority)
				return 1;
			if (!dlData.bIsFirstPriority && bIsFirstPriority)
				return -1;

			int value = dlData.dl.getSeedingRank() - dl.getSeedingRank();
			if (value == 0) {
				return dl.getPosition() - dlData.getDownloadObject().getPosition();
			}
			return value;
    }

    public downloadData(Download _dl)
    {
      dl = _dl;
      //recalcSeedingRank();
    }
    
    Download getDownloadObject() {
      return dl;
    }
    
    private boolean getActivelyDownloading() {
      boolean bIsActive = false;
      DownloadStats stats = dl.getStats();
      int state = dl.getState();
      
      // In order to be active,
      // - Must be downloading (and thus incomplete)
      // - Must be above speed threshold, or started less than 30s ago
      if (state != Download.ST_DOWNLOADING) {
      	bIsActive = false;
			} else if (System.currentTimeMillis() - stats.getTimeStarted() <= FORCE_ACTIVE_FOR) {
				bIsActive = true;
      } else {
      	// activity based on DL Average
      	bIsActive = (stats.getDownloadAverage() >= minSpeedForActiveDL);
      	
    		if (bActivelyDownloading != bIsActive) {
  				long now = System.currentTimeMillis();
    			// Change
					if (lDLActivelyChangedOn == -1) {
						// Start Timer
						lDLActivelyChangedOn = now;
						bIsActive = !bIsActive;
					} else if (now - lDLActivelyChangedOn < ACTIVE_CHANGE_WAIT) {
						// Continue as old state until timer finishes
						bIsActive = !bIsActive;
					}
    		} else {
    			// no change, reset timer
					lDLActivelyChangedOn = -1;
    		}
      }
      

      if (bActivelyDownloading != bIsActive) {
        bActivelyDownloading = bIsActive;
        somethingChanged = true;
        if (bDebugLog) 
          log.log(dl.getTorrent(), LoggerChannel.LT_INFORMATION,
                  "somethingChanged: ActivelyDownloading changed");
      }
      return bActivelyDownloading;
    }
    
    private boolean getActivelySeeding() {
      boolean bIsActive = false;
      DownloadStats stats = dl.getStats();
      int state = dl.getState();
      // Timed torrents don't use a speed threshold, since they are based on time!
      // However, First Priorities need to be checked for activity so that 
      // timed ones can start when FPs are below threshold.  Ditto for 0 Peers
      // when bAutoStart0Peers
      if (iRankType == RANK_TIMED && !isFirstPriority() &&
          !(bAutoStart0Peers && calcPeersNoUs(dl) == 0 && scrapeResultOk(dl))) {
      	bIsActive = (state == Download.ST_SEEDING);

      } else if (state != Download.ST_SEEDING
					|| (bAutoStart0Peers && calcPeersNoUs(dl) == 0)) {
				// Not active if we aren't seeding
				// Not active if we are AutoStarting 0 Peers, and peer count == 0
				bIsActive = false;
			} else if (System.currentTimeMillis() - stats.getTimeStarted() <= FORCE_ACTIVE_FOR) {
				bIsActive = true;
			} else {
				bIsActive = (stats.getUploadAverage() >= minSpeedForActiveSeeding);

    		if (bActivelySeeding != bIsActive) {
  				long now = System.currentTimeMillis();
    			// Change
					if (lCDActivelyChangedOn == -1) {
						// Start Timer
						lCDActivelyChangedOn = now;
						bIsActive = !bIsActive;
					} else if (now - lCDActivelyChangedOn < ACTIVE_CHANGE_WAIT) {
						// Continue as old state until timer finishes
						bIsActive = !bIsActive;
					}
    		} else {
    			// no change, reset timer
					lCDActivelyChangedOn = -1;
    		}
			}

      if (bActivelySeeding != bIsActive) {
        bActivelySeeding = bIsActive;
        somethingChanged = true;
        if (bDebugLog) 
          log.log(dl.getTorrent(), LoggerChannel.LT_INFORMATION,
                  "somethingChanged: ActivelySeeding changed");
      }
      return bActivelySeeding;
    }
    
    /** Assign Seeding Rank based on RankType
     * @return New Seeding Rank Value
     */
    public int recalcSeedingRank() {
			try {
				downloadData_this_mon.enter();

				int oldSR = dl.getSeedingRank();
				DownloadStats stats = dl.getStats();
				int numCompleted = stats.getDownloadCompleted(false);

				// make undownloaded sort to top so they can start first.

				if (numCompleted < 1000) {
					dl.setSeedingRank(SR_INCOMPLETE_ENDS_AT - dl.getPosition());
					return oldSR;
				}

				// here we are seeding

				int shareRatio = stats.getShareRatio();

				int numPeers = calcPeersNoUs(dl);
				int numSeeds = calcSeedsNoUs(dl);

				boolean bScrapeResultsOk = (numPeers > 0) || (numSeeds > 0)
						|| scrapeResultOk(dl);

				int newSR = 0;

				if (!isFirstPriority()) {

					/** 
					 * XXX Check ignore rules
					 */
					// never apply ignore rules to First Priority Matches
					// (we don't want leechers circumventing the 0.5 rule)
					if (iIgnoreShareRatio != 0
							&& shareRatio >= iIgnoreShareRatio
							&& (numSeeds >= iIgnoreShareRatio_SeedStart || !scrapeResultOk(dl))
							&& shareRatio != -1) {
						dl.setSeedingRank(SR_SHARERATIOMET);
						return SR_SHARERATIOMET;
					}

					if (numPeers == 0 && bScrapeResultsOk) {
						if (shareRatio >= minQueueingShareRatio && shareRatio != -1
								&& bIgnore0Peers) {
							dl.setSeedingRank(SR_0PEERS);
							return SR_0PEERS;
						}

						if (bFirstPriorityIgnore0Peer
								&& (shareRatio < minQueueingShareRatio) && shareRatio != -1) {
							dl.setSeedingRank(SR_FP0PEERS);
							return SR_FP0PEERS;
						}
					}

					if (numPeers != 0 && iFirstPriorityIgnoreSPRatio != 0
							&& numSeeds / numPeers >= iFirstPriorityIgnoreSPRatio) {
						dl.setSeedingRank(SR_SPRATIOMET);
						return SR_SPRATIOMET;
					}

					//0 means disabled
					if ((iIgnoreSeedCount != 0) && (numSeeds >= iIgnoreSeedCount)) {
						dl.setSeedingRank(SR_NUMSEEDSMET);
						return SR_NUMSEEDSMET;
					}

					// Skip if Stop Peers Ratio exceeded
					// (More Peers for each Seed than specified in Config)
					//0 means never stop
					if (iIgnoreRatioPeers != 0 && numSeeds != 0) {
						float ratio = (float) numPeers / numSeeds;
						if (ratio <= iIgnoreRatioPeers
								&& numSeeds >= iIgnoreRatioPeers_SeedStart) {
							dl.setSeedingRank(SR_RATIOMET);
							return SR_RATIOMET;
						}
					}
				}

				// Never do anything with rank type of none
				if (iRankType == RANK_NONE) {
					// everythink ok!
					dl.setSeedingRank(newSR);
					return newSR;
				}

				if (iRankType == RANK_TIMED) {
					if (bIsFirstPriority) {
						dl.setSeedingRank(newSR + SR_TIMED_QUEUED_ENDS_AT + 1);
						return newSR;
					}

					int state = dl.getState();
					if (state == Download.ST_STOPPING || state == Download.ST_STOPPED
							|| state == Download.ST_ERROR) {
						dl.setSeedingRank(SR_NOTQUEUED);
						return SR_NOTQUEUED;
					} else if (state == Download.ST_SEEDING || state == Download.ST_READY
							|| state == Download.ST_WAITING || state == Download.ST_PREPARING) {
						// force sort to top
						long lMsElapsed = 0;
						if (state == Download.ST_SEEDING && !dl.isForceStart())
							lMsElapsed = (SystemTime.getCurrentTime() - stats
									.getTimeStartedSeeding());

						if (lMsElapsed >= minTimeAlive) {
							dl.setSeedingRank(1);
							if (oldSR > SR_TIMED_QUEUED_ENDS_AT) {
								somethingChanged = true;
								if (bDebugLog)
									log.log(dl.getTorrent(), LoggerChannel.LT_INFORMATION,
											"somethingChanged: TimeUp");
							}
						} else {
							newSR = SR_TIMED_QUEUED_ENDS_AT + 1 + (int) (lMsElapsed / 1000);
							dl.setSeedingRank(newSR);
							if (oldSR <= SR_TIMED_QUEUED_ENDS_AT) {
								somethingChanged = true;
								if (bDebugLog)
									log.log(dl.getTorrent(), LoggerChannel.LT_INFORMATION,
											"somethingChanged: strange timer change");
							}
						}
						return newSR;
					} else {
						if (oldSR <= 0) {
							newSR = SR_TIMED_QUEUED_ENDS_AT - dl.getPosition();
							dl.setSeedingRank(newSR);
							somethingChanged = true;
							if (bDebugLog)
								log.log(dl.getTorrent(), LoggerChannel.LT_INFORMATION,
										"somethingChanged: NotIgnored");
						}
						return newSR;
					}
				}

				/** 
				 * Add to SeedingRank based on Rank Type
				 */

				// SeedCount and SPRatio require Scrape Results..
				if (bScrapeResultsOk) {
					if ((iRankType == RANK_SEEDCOUNT)
							&& (iRankTypeSeedFallback == 0 || iRankTypeSeedFallback > numSeeds)) {
	          // Worst case: iLimit+((iLimit)/(num_seeds+2))+num_peers
						// Max # = 2*(iLimit+1)
						//   iLimit+((iLimit)/(num_seeds+2))+num_peers < 2*(iLimit+1)
						//   iLimit+((iLimit)/(num_seeds+2))+num_peers < 2*iLimit +2
						//   iLimit+((iLimit)/(num_seeds+2)) < 2*iLimit +2 - num_peers
						//   iLimit+((iLimit)/(num_seeds+2)) < iLimit + iLimit +2 - num_peers
						//   ((iLimit)/(num_seeds+2))-iLimit - 2 < -num_peers
						//   num_peers < +iLimit + 2-((iLimit)/(num_seeds+2))
						int maxp = SEEDCOUNT_LIMIT2 + 2 - ((SEEDCOUNT_LIMIT2)/(numSeeds+2));
						int iMaxPeers = (int) maxp - 1;

						if (bDebugLog) {
							sExplainSR = SEEDCOUNT_LIMIT2 + ";";
							sExplainSR += "SR Calc: numSeeds=" + numSeeds + ";MaxPeers="
									+ iMaxPeers + ";NumPeers=" + numPeers;
						}
						
						int iNumPeers = numPeers;
						if (iNumPeers >= iMaxPeers)
							iNumPeers = iMaxPeers - 1;
						if (!bPreferLargerSwarms && iNumPeers >= SEEDCOUNT_LIMIT2)
							iNumPeers = SEEDCOUNT_LIMIT2 - 1;
						if (bDebugLog) sExplainSR += ";AdjNumPeers=" + iNumPeers;

						long phase1 = ((SEEDCOUNT_LIMIT2)
								/ (numSeeds + (bPreferLargerSwarms ? 2 : 1)));
						if (bDebugLog) sExplainSR += "\nSR(P1)=" + phase1;

						phase1 += ((bPreferLargerSwarms ? 1 : -1) * iNumPeers);
						if (bDebugLog) sExplainSR += ";SR(P2)=" + phase1;

						if (numSeeds == 0 && numPeers >= minPeersToBoostNoSeeds)
							phase1 += SEEDCOUNT_LIMIT2;
						if (bDebugLog) sExplainSR += ";SR(P3)=" + phase1;

						// Shift phase1 over by 3 digits, then add "reversed share ratio / 10"
						// to it (limited to those 3 digits), so that equal phase1 torrents 
						// with smaller share ratios will move to the top
						int shareRatioModifier =  9999 - dl.getStats().getShareRatio();
						if (shareRatioModifier <= 0) // 10.0
							shareRatioModifier = 0;
						else
							shareRatioModifier /= 10;
						newSR += (phase1 * 1000) + shareRatioModifier;
						
						// Finally, add in SPRATIO_LIMIT1 so that fallback works
						newSR += SPRATIO_BASE_LIMIT;

						if (bDebugLog) sExplainSR += ";shareRM=" + shareRatioModifier + 
								";SR(Final)=" + newSR + "\n";
					} else { // iRankType == RANK_SPRATIO or we are falling back
						if (numPeers != 0) {
							if (numSeeds == 0) {
								if (numPeers >= minPeersToBoostNoSeeds)
									newSR += SPRATIO_BASE_LIMIT + 1;
							} else { // numSeeds != 0 && numPeers != 0
								float x = (float)numSeeds / numPeers;
								newSR += SPRATIO_BASE_LIMIT / ((x + 1) * (x + 1));
							}

							// Shift over, leaving room for peers.
							newSR *= SPRATIO_SHIFT;
							if (bPreferLargerSwarms) {
								if (numPeers >= SPRATIO_SHIFT / 2)
									numPeers = 5000;
								newSR += numPeers * 2;
							} else { // Prefer smaller swarms
								if (numPeers >= SPRATIO_SHIFT)
									numPeers = 10000;
								newSR += numPeers;
							}
						}
					}

				}

				if (newSR < 0)
					newSR = 1;
				
				if (newSR != oldSR)
					dl.setSeedingRank(newSR);
				return newSR;
			} finally {

				downloadData_this_mon.exit();
			}
		} // recalcSeedingRank

    /** Does the torrent match First Priority criteria? */
    public boolean isFirstPriority() {
    	boolean bFP = pisFirstPriority();
    	
      if (bIsFirstPriority != bFP) {
      	bIsFirstPriority = bFP;
        somethingChanged = true;
        if (bDebugLog) 
          log.log(dl.getTorrent(), LoggerChannel.LT_INFORMATION,
                  "somethingChanged: FP changed");
      }
      return bIsFirstPriority;
    }

    private boolean pisFirstPriority() {
      if (bDebugLog) sExplainFP = "FP Calculations.  Using " + 
                                  (iFirstPriorityType == FIRSTPRIORITY_ALL ? "All": "Any") + 
                                  ":\n";
      
      if (!dl.isPersistent()) {
        if (bDebugLog) sExplainFP += "Not FP: Download not persistent\n";
        return false;
      }

      // FP only applies to completed
      if (dl.getStats().getDownloadCompleted(false) < 1000) {
        if (bDebugLog) sExplainFP += "Not FP: Download not complete\n";
        return false;
      }

      if (dl.getState() == Download.ST_ERROR ||
          dl.getState() == Download.ST_STOPPED) {
        if (bDebugLog) sExplainFP += "Not FP: Download is ERROR or STOPPED\n";
        return false;
      }
      
      // FP doesn't apply when S:P >= set SPratio (SPratio = 0 means ignore)
      int numPeers = calcPeersNoUs(dl);
      int numSeeds = calcSeedsNoUs(dl);
	  if (numPeers > 0 && numSeeds > 0 && (numSeeds / numPeers) >= iFirstPriorityIgnoreSPRatio && iFirstPriorityIgnoreSPRatio != 0) {
      if (bDebugLog) sExplainFP += "Not FP: S:P >= "+iFirstPriorityIgnoreSPRatio+":1\n";
        return false;
      }

      //not FP if no peers  //Nolar, 2105 - Gouss, 2203
      if( numPeers == 0 && scrapeResultOk(dl) && bFirstPriorityIgnore0Peer) {
        if (bDebugLog) sExplainFP += "Not FP: 0 peers\n";
        return false;
      }
      
      int shareRatio = dl.getStats().getShareRatio();
      boolean bLastMatched = (shareRatio != -1) && (shareRatio < minQueueingShareRatio);
      
      if (bDebugLog) sExplainFP += "  shareRatio("+shareRatio+") < "+minQueueingShareRatio+"="+bLastMatched+"\n";
      if (!bLastMatched && iFirstPriorityType == FIRSTPRIORITY_ALL) {
        if (bDebugLog) sExplainFP += "..Not FP.  Exit Early\n";
        return false;
      }
      if (bLastMatched && iFirstPriorityType == FIRSTPRIORITY_ANY) {
        if (bDebugLog) sExplainFP += "..Is FP.  Exit Early\n";
        return true;
      }
      
      bLastMatched = (iFirstPrioritySeedingMinutes == 0);
      if (!bLastMatched) {
        long timeSeeding = dl.getStats().getSecondsOnlySeeding();
        if (timeSeeding > 0) {
          bLastMatched = (timeSeeding < (iFirstPrioritySeedingMinutes * 60));
          if (bDebugLog) sExplainFP += "  SeedingTime("+timeSeeding+") < "+(iFirstPrioritySeedingMinutes*60)+"="+bLastMatched+"\n";
          if (!bLastMatched && iFirstPriorityType == FIRSTPRIORITY_ALL) {
            if (bDebugLog) sExplainFP += "..Not FP.  Exit Early\n";
            return false;
          }
          if (bLastMatched && iFirstPriorityType == FIRSTPRIORITY_ANY) {
            if (bDebugLog) sExplainFP += "..Is FP.  Exit Early\n";
            return true;
          }
        }
      } else if (bDebugLog) {
        sExplainFP += "  SeedingTime setting == 0:  Ignored";
      }

      bLastMatched = (iFirstPriorityDLMinutes == 0);
      if (!bLastMatched) {
        long timeDLing = dl.getStats().getSecondsDownloading();
        if (timeDLing > 0) {
          bLastMatched = (timeDLing < (iFirstPriorityDLMinutes * 60));
          if (bDebugLog) sExplainFP += "  DLTime("+timeDLing+") < "+(iFirstPriorityDLMinutes*60)+"="+bLastMatched+"\n";
          if (!bLastMatched && iFirstPriorityType == FIRSTPRIORITY_ALL) {
            if (bDebugLog) sExplainFP += "..Not FP.  Exit Early\n";
            return false;
          }
          if (bLastMatched && iFirstPriorityType == FIRSTPRIORITY_ANY) {
            if (bDebugLog) sExplainFP += "..Is FP.  Exit Early\n";
            return true;
          }
        }
      } else if (bDebugLog) {
        sExplainFP += "  DLTime setting == 0:  Ignored";
      }

      if (iFirstPriorityType == FIRSTPRIORITY_ALL) {
        if (bDebugLog) sExplainFP += "..Is FP\n";
        return true;
      }

      if (bDebugLog) sExplainFP += "..Not FP\n";
      return false;
    }
    
    public boolean getCachedIsFP() {
    	return bIsFirstPriority;
    }

		public String toString() {
			return String.valueOf(dl.getSeedingRank());
		}
  }
} // class

