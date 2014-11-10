// Copyright 2011-2012, Art Hare
// This file is part of WifiLapper.

//WifiLapper is free software: you can redistribute it and/or modify
//it under the terms of the GNU General Public License as published by
//the Free Software Foundation, either version 3 of the License, or
//(at your option) any later version.

//WifiLapper is distributed in the hope that it will be useful,
//but WITHOUT ANY WARRANTY; without even the implied warranty of
//MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//GNU General Public License for more details.

//You should have received a copy of the GNU General Public License
//along with WifiLapper.  If not, see <http://www.gnu.org/licenses/>.

package com.artsoft.wifilapper;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.Region.Op;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Message;
import android.os.Parcelable;
import android.location.*;
import android.util.AttributeSet;
import android.util.Log;
import android.view.*;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Toast;
import android.graphics.*;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

import com.artsoft.wifilapper.IOIOManager.IOIOListener;
import com.artsoft.wifilapper.LapAccumulator.DataChannel;
import com.artsoft.wifilapper.LapSender.LapSenderListener;


public class ApiDemos 
extends Activity 
implements 
	LocationListener, 
	Utility.MultiStateObject, 
	OnClickListener, 
	Handler.Callback, 
	SensorEventListener,
	MessageMan.MessageReceiver, 
	OBDThread.OBDListener, IOIOListener, LapSenderListener

{

	OrientationEventListener myOrientationEventListener;

	// For aggressive wifi scanning
	Thread m_wifiScanThd;
	static WifiManager mainWifiObj;
	private final boolean WIFILOGGING = false;
	
	public enum RESUME_MODE {NEW_RACE, REUSE_SPLITS, RESUME_RACE};
	
	enum State {LOADING, WAITING_FOR_GPS, WAITINGFORSTART, WAITINGFORSTOP, MOVING_TO_STARTLINE, PLOTTING, DEMOSCREEN};
	
	private State m_eState;
	
	private LapSender m_lapSender;
	private MessageMan m_msgMan;
	private BluetoothGPS m_btgps = null;
	private OBDThread m_obd = null;
	private IOIOManager m_ioio = null;
	
	private SplitDecider m_startDecider;
	private SplitDecider m_stopDecider;

	private static int iMaxPointsPerLap = 10*60*15;  // 10Hz  * 60s/min * 15 min
	
	private boolean m_fUseP2P;
	private int m_iP2PStartMode;
	private float m_flP2PStartParam;
	private int m_iP2PStopMode;
	private float m_flP2PStopParam;
	
	// message-man stuff
	private String m_strMessage;
	private String m_strMessagePhone; // phone number that sent this message
	private String m_strPrivacyPrefix; // the privacy prefix to use when determining whether to show a text
	private boolean m_fAcknowledgeBySMS; // whether this instance of the app will acknowledge SMSes with SMSes
	private boolean m_fSupportSMS; // whether we support SMS stuff
	private int m_iMsgTime; // the time, in (System.currentTimeMillis()/1000) until which we will display the message
	
	private View m_currentView;
	private StatusBarView m_statusBar;
	// threads
	private Handler m_pHandler;
		
	// lapping
	private LapAccumulator m_myLaps = null; // current lap
	private LapAccumulator.DataChannel m_XAccel;
	private LapAccumulator.DataChannel m_YAccel;
	private LapAccumulator.DataChannel m_ZAccel;
	
	private LapAccumulator.DataChannel m_XReception;
	private LapAccumulator.DataChannel m_YReception;
	private Map<Integer,DataChannel> m_mapPIDS;
	private Map<Integer,DataChannel> m_mapPins;
	
	private long m_tmLastLap = 0; // the system time that we saved the last lap at
	private LapAccumulator m_lastLap = null;
	private LapAccumulator m_best = null;
	
	private LapAccumulator.LapAccumulatorParams m_lapParams = null;

	Point2D m_ptCurrent; // the most recent point to come in from the location manager
	public float m_tmCurrent; // the time (in seconds) of the m_ptCurrent
	Point2D m_ptLast; // the point that came before that one
	public float m_tmLast; // the time (in seconds) of m_ptLast
	
	float m_dLastSpeed; // how fast (in m/s) we were going at the last GPS point
	float m_dLastTime; // the time (in seconds) of the last point we received
	
	private boolean m_fRecordReception; // whether we're currently recording reception.  This gets set when the wifi class tells us it is connected/disconnected
	
	// Acceleration sensor data
	private float xAccelCum=0;  // moving average of accel data to smooth
	private float yAccelCum=0;  // and reduce database sizs
	private float zAccelCum=0;
	private boolean fUseAccelCorrection;
	private float flPitch;
	private float flRoll;
	private float flSensorOffset[] = new float[3];
	private int iFilterType;
	enum FILTER_TYPE {NONE,KAISER,MOVING_SHORT,MOVING_LONG};
	private FILTER_TYPE eFilterType = FILTER_TYPE.NONE;		
	
    private int   iLastIOIOSubmissionTime[]   = new int[48];
	private float flLastIOIOSubmissionValue[] = new float[48];
	private boolean bDeferring[] = new boolean[48];

	// data about this race
	private String m_strRaceName;
	private boolean m_fTestMode;
	private boolean m_bWifiScan;
	private long m_lRaceId;
	private String m_strSpeedoStyle;
	private Prefs.UNIT_SYSTEM m_eUnitSystem;
	
	private boolean bPortraitDisplay;	// holds the screen orientation, used for re-mapping accelerometer
	
	private long rgLastGPSGaps[] = new long[10];
	private int iLastGPSGap = 0;
	// the time (in milliseconds since 1970) of the first location received
	// times recorded in LapAccumulators are expressed in milliseconds since this time.
	// this allows us to store them as 32-bit integers (since it is unlikely anyone will be participating in a 2^32 millisecond-long race)
	long m_tmAppStartTime = 0; 
	// the time (in milliseconds since the phone startup) of the first location received.
	// times sent to us by the SensorManager are expressed in time since the phone started,
	// so we need this value to convert them into "time since first location" like the other data in the app
	long m_tmAppStartUptime = 0;
	
	private FakeLocationGenerator m_thdFakeLocs = null;
	
	private static final int MSG_STATE_CHANGED = 50;
	public static final int MSG_FAKE_LOCATION = 51;
	private static final int MSG_LOADING_PROGRESS = 52;
	private static final int MSG_IOIO_BUTTON = 53;
	
	private PendingIntent m_restartIntent;
	
	private static final int RESTART_NOTIFICATION_ID = 1;
	
	private static ApiDemos m_me;
	

	private void lockOrientation(int originalRotation, int naturalOppositeRotation) {
	    int orientation = getResources().getConfiguration().orientation;
	    if (orientation == Configuration.ORIENTATION_PORTRAIT) {
	        // Are we reverse?
	        if (originalRotation == Surface.ROTATION_0 || originalRotation == naturalOppositeRotation) {
	            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
	        } else {
	            setReversePortrait();
	        }
	    } else {
	        // Are we reverse?
	        if (originalRotation == Surface.ROTATION_0 || originalRotation == naturalOppositeRotation) {
	            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
	        } else {
	            setReverseLandscape();
	        }
	    }
	}

	@TargetApi(Build.VERSION_CODES.GINGERBREAD)
	private void setReversePortrait() {
	    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
	        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT);
	    } else {
	        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
	    }
	}

	@TargetApi(Build.VERSION_CODES.GINGERBREAD)
	private void setReverseLandscape() {
	    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD) {
	        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE);
	    } else {
	        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
	    }
	}
	/** Called when the activity is first created. */
	@Override
	public void onCreate(Bundle savedInstanceState) 
	{
		super.onCreate(savedInstanceState);

		synchronized(ApiDemos.class)
		{
			m_me = this;
		}
    			
    	if( BuildConfig.DEBUG && WIFILOGGING ) {
    		try {
    			openLogFile("Wifi_log.txt");
    		} catch (IOException e1) {
    			e1.printStackTrace();
    		}
    	}
    	m_tmAppStartTime = 0;
		
    	getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    	m_pHandler = new Handler(this);

    	Intent i = this.getIntent();
    	String strIP = i.getStringExtra(Prefs.IT_IP_STRING);
    	String strSSID = i.getStringExtra(Prefs.IT_SSID_STRING);
    	String strBTGPS = i.getStringExtra(Prefs.IT_BTGPS_STRING);
    	String strOBD2 = i.getStringExtra(Prefs.IT_BTOBD2_STRING);
    	
    	final boolean fUseIOIO = i.getBooleanExtra(Prefs.PREF_USEIOIO_BOOLEAN, Prefs.DEFAULT_USEIOIO_BOOLEAN);
    	for( int init=1;init<=47;init++ ) {
    		iLastIOIOSubmissionTime[init]   = -1;
    		flLastIOIOSubmissionValue[init] = -1;
    		bDeferring[init] = false;
    	}
    	
    	//ArrayList<Point2D> testArray = new ArrayList<Point2D>(1000000); 
    	//TimePoint2D: 100000 was 400KB, 1e6 was about 4MB
    	//Point2D was the same size. 16 bytes bigger than 4 bytes per element
    	//testArray.add(new Point2D(0,0));
    	    	
    	fUseAccelCorrection =  i.getBooleanExtra(Prefs.IT_ACCEL_CORRECTION, Prefs.DEFAULT_ACCEL_CORRECTION);
    	flPitch = i.getFloatExtra(Prefs.IT_ACCEL_CORRECTION_PITCH, Prefs.DEFAULT_ACCEL_CORRECTION_PITCH);
    	flRoll  = i.getFloatExtra(Prefs.IT_ACCEL_CORRECTION_ROLL, Prefs.DEFAULT_ACCEL_CORRECTION_ROLL);
    	flSensorOffset[1]  = i.getFloatExtra(Prefs.IT_ACCEL_OFFSET_X, Prefs.DEFAULT_ACCEL_OFFSET_X);
    	flSensorOffset[2]  = i.getFloatExtra(Prefs.IT_ACCEL_OFFSET_Y, Prefs.DEFAULT_ACCEL_OFFSET_Y);
    	flSensorOffset[0]  = i.getFloatExtra(Prefs.IT_ACCEL_OFFSET_Z, Prefs.DEFAULT_ACCEL_OFFSET_Z);
		iFilterType = i.getIntExtra(Prefs.IT_ACCEL_FILTER,Prefs.DEFAULT_ACCEL_FILTER);
		for( int f=0;f<FILTER_TYPE.values().length;f++)
			if( f==iFilterType ) eFilterType = FILTER_TYPE.values()[f];
		

    	
    	final boolean fUseAccel = i.getBooleanExtra(Prefs.IT_USEACCEL_BOOLEAN, Prefs.DEFAULT_USEACCEL);
    	m_strRaceName = i.getStringExtra(Prefs.IT_RACENAME_STRING);
    	m_lRaceId = i.getLongExtra(Prefs.IT_RACEID_LONG, -1);
    	m_fTestMode = i.getBooleanExtra(Prefs.IT_TESTMODE_BOOL, false);
    	m_bWifiScan = i.getBooleanExtra(Prefs.IT_WIFI_SCAN_BOOL, false);
    	
    	m_strSpeedoStyle = i.getStringExtra(Prefs.IT_SPEEDOSTYLE_STRING);
    	m_fAcknowledgeBySMS = i.getBooleanExtra(Prefs.IT_ACKSMS_BOOLEAN, true);
    	m_strPrivacyPrefix = i.getStringExtra(Prefs.IT_PRIVACYPREFIX_STRING);
    	
    	m_fUseP2P = i.getBooleanExtra(Prefs.IT_P2P_ENABLED, false);
    	m_iP2PStartMode = i.getIntExtra(Prefs.IT_P2P_STARTMODE, Prefs.DEFAULT_P2P_STARTMODE);
    	m_flP2PStartParam = i.getFloatExtra(Prefs.IT_P2P_STARTPARAM, Prefs.DEFAULT_P2P_STARTPARAM);
    	m_iP2PStopMode = i.getIntExtra(Prefs.IT_P2P_STOPMODE, Prefs.DEFAULT_P2P_STOPMODE);
    	m_flP2PStopParam = i.getFloatExtra(Prefs.IT_P2P_STOPPARAM, Prefs.DEFAULT_P2P_STOPPARAM);
    	boolean fRequireWifi = i.getBooleanExtra(Prefs.IT_REQUIRE_WIFI, Prefs.DEFAULT_REQUIRE_WIFI);
    	
    	if(m_strPrivacyPrefix == null) m_strPrivacyPrefix = Prefs.DEFAULT_PRIVACYPREFIX;
    	
    	if(m_strSpeedoStyle == null) m_strSpeedoStyle = LandingOptions.rgstrSpeedos[0];

    	// if I'm stuck API8, there's no way to lock reverse landscape. Though this looks like
    	// a good reference: http://stackoverflow.com/questions/6599770/screen-orientation-lock
//		int orientation = getResources().getConfiguration().orientation;
//		if (orientation == Configuration.ORIENTATION_PORTRAIT) 
//			setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
//		else
//			setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);

		int rotation = getWindowManager().getDefaultDisplay().getRotation();
		lockOrientation(rotation, Surface.ROTATION_270);
		// Ensure that the rotation hasn't changed
		if (getWindowManager().getDefaultDisplay().getRotation() != rotation) {
		    lockOrientation(rotation, Surface.ROTATION_90);
		}
    	
    	String strUnitSystem = i.getStringExtra(Prefs.IT_UNITS_STRING);
    	int rgSelectedPIDs[] = i.getIntArrayExtra(Prefs.IT_SELECTEDPIDS_ARRAY);
    	
    	Parcelable[] rgAnalParcel = i.getParcelableArrayExtra(Prefs.IT_IOIOANALPINS_ARRAY); // an array of indices indicating which pins we want to use
		Parcelable[] rgPulseParcel = i.getParcelableArrayExtra(Prefs.IT_IOIOPULSEPINS_ARRAY); // an array of indices indicating which pins we want to use
		IOIOManager.PinParams rgSelectedAnalPins[] = new IOIOManager.PinParams[rgAnalParcel.length];
		IOIOManager.PinParams rgSelectedPulsePins[] = new IOIOManager.PinParams[rgPulseParcel.length];
		int iButtonPin = i.getIntExtra(Prefs.IT_IOIOBUTTONPIN, Prefs.DEFAULT_IOIOBUTTONPIN);
		
		for(int x = 0;x < rgAnalParcel.length; x++) rgSelectedAnalPins[x] = (IOIOManager.PinParams)rgAnalParcel[x];
		for(int x = 0;x < rgPulseParcel.length; x++) rgSelectedPulsePins[x] = (IOIOManager.PinParams)rgPulseParcel[x];
		
    	m_eUnitSystem = Prefs.UNIT_SYSTEM.valueOf(strUnitSystem);
    	
    	int idLapLoadMode = (int)i.getLongExtra(Prefs.IT_LAPLOADMODE_LONG,RESUME_MODE.REUSE_SPLITS.ordinal());
    	
    	if(idLapLoadMode == RESUME_MODE.REUSE_SPLITS.ordinal())
    	{
    		// they only want to use the waypoints from this race.
    		m_lRaceId = -1;
    	}
    	
		// Lock the screen orientation, so it doesn't change during a race
//    	Display display = getWindowManager().getDefaultDisplay();
//    	
//    	if ( display.getRotation() == Surface.ROTATION_0 ) {
//		    setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
//		    bPortraitDisplay = true;
//		} else {
//			setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
//			bPortraitDisplay = false;
//		}
//	    setRequestedOrientation(getResources().getConfiguration().orientation);

		// Set up aggressive wifi logging, if enabled
    	mainWifiObj = (WifiManager) getSystemService(Context.WIFI_SERVICE);
		m_wifiScanThd = new myWifiScan();
		if( m_bWifiScan )
			m_wifiScanThd.start();
		
    	try
    	{
	    	ActivityInfo info = getPackageManager().getActivityInfo(getComponentName(), PackageManager.GET_META_DATA);
	    	Bundle bundle = info.metaData;
	    	String apikey = bundle.getString("appmode");
	    	m_fSupportSMS = !apikey.equals("tablet");
    	}
    	catch(Exception e)
    	{
    		m_fSupportSMS = true; // default to trying to SMS
    	}
    	
    	m_lapParams = (LapAccumulator.LapAccumulatorParams)i.getParcelableExtra(Prefs.IT_LAPPARAMS);
    	
    	StartupTracking(fRequireWifi, fUseIOIO, rgSelectedAnalPins, rgSelectedPulsePins, iButtonPin, rgSelectedPIDs, strIP, strSSID, strBTGPS, strOBD2, fUseAccel, fUseAccelCorrection, iFilterType, flPitch, flRoll, m_fTestMode, idLapLoadMode);
    }

    // Support logging to a file
    static BufferedWriter bLogFile;
    static long lLogStart=0;
    private void openLogFile(String filename) throws IOException {
    		String savePath = Environment.getExternalStorageDirectory().getPath()+"/WifiLapperCrashes/";
    		lLogStart = System.currentTimeMillis();
            bLogFile = new BufferedWriter(new FileWriter(savePath + "/" + filename));
            bLogFile.write("Start of Log\n");
    }
    private void appendToLog(String strAppend) throws IOException {
    	if( bLogFile == null )
    		return;
    	bLogFile.write(strAppend);
    }
    
    private void closeLogFile() {
    	if( bLogFile == null )
    		return;
    	try {
    		bLogFile.write("End of log");
        	bLogFile.flush();
			bLogFile.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
    }

    volatile boolean bScanning = false;
	boolean bWifiScanKill = false;

	public void ShutdownScan() {
		bWifiScanKill = true;
	}

	private class myWifiScan extends Thread implements Runnable {
		public void run() {
			Thread.currentThread().setName("Scan Thread");
			while( !bWifiScanKill ) {
				if( bScanning) 
					mainWifiObj.startScan();
				try {
					Thread.sleep(500);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		}
	}

    public static Intent BuildStartIntent(boolean fRequireWifi, boolean fUseIOIO, IOIOManager.PinParams rgAnalPins[], IOIOManager.PinParams rgPulsePins[], int iButtonPin, boolean fPointToPoint, int iStartMode, float flStartParam, int iStopMode, float flStopParam, List<Integer> lstSelectedPIDs, Context ctxApp, String strIP, String strSSID, LapAccumulator.LapAccumulatorParams lapParams, String strRaceName, String strPrivacy, boolean fAckSMS, boolean fUseAccel, boolean fUseAccelCorrection, int iFilterType, float flPitch, float flRoll, float[] flSensorOffset, boolean fTestMode, boolean bWifiScan, long idRace, long idModeSelected, String strBTGPS, String strBTOBD2, String strSpeedoStyle, String strUnitSystem)
    {
    	Intent myIntent = new Intent(ctxApp, ApiDemos.class);
    	
    	myIntent.putExtra(Prefs.IT_REQUIRE_WIFI, fRequireWifi);
		myIntent.putExtra(Prefs.IT_IP_STRING, strIP).putExtra("SSID", strSSID);
		myIntent.putExtra(Prefs.IT_LAPPARAMS, lapParams);
		myIntent.putExtra(Prefs.IT_RACENAME_STRING, strRaceName);
		myIntent.putExtra(Prefs.IT_TESTMODE_BOOL, (boolean)fTestMode);
		myIntent.putExtra(Prefs.IT_WIFI_SCAN_BOOL, (boolean)bWifiScan);
		myIntent.putExtra(Prefs.IT_RACEID_LONG, (long)idRace);
		myIntent.putExtra(Prefs.IT_LAPLOADMODE_LONG, (long)idModeSelected);
		myIntent.putExtra(Prefs.IT_BTGPS_STRING, strBTGPS);
		myIntent.putExtra(Prefs.IT_BTOBD2_STRING, strBTOBD2);
		myIntent.putExtra(Prefs.IT_SPEEDOSTYLE_STRING, strSpeedoStyle);
		myIntent.putExtra(Prefs.IT_UNITS_STRING, strUnitSystem);
		myIntent.putExtra(Prefs.IT_USEACCEL_BOOLEAN, fUseAccel);
		myIntent.putExtra(Prefs.PREF_USEIOIO_BOOLEAN, fUseIOIO);
		myIntent.putExtra(Prefs.PREF_ACCEL_CORRECTION, fUseAccelCorrection);
		myIntent.putExtra(Prefs.PREF_ACCEL_FILTER, iFilterType);
		myIntent.putExtra(Prefs.PREF_ACCEL_CORRECTION_PITCH, flPitch);
		myIntent.putExtra(Prefs.PREF_ACCEL_CORRECTION_ROLL, flRoll);
		myIntent.putExtra(Prefs.PREF_ACCEL_OFFSET_X, flSensorOffset[1]);
		myIntent.putExtra(Prefs.PREF_ACCEL_OFFSET_Y, flSensorOffset[2]);
		myIntent.putExtra(Prefs.PREF_ACCEL_OFFSET_Z, flSensorOffset[0]);
		

		myIntent.putExtra(Prefs.IT_ACKSMS_BOOLEAN, fAckSMS);
		myIntent.putExtra(Prefs.IT_PRIVACYPREFIX_STRING, strPrivacy);
		myIntent.putExtra(Prefs.IT_IOIOBUTTONPIN, iButtonPin);
		myIntent.putExtra(Prefs.IT_P2P_ENABLED, fPointToPoint);
		myIntent.putExtra(Prefs.IT_P2P_STARTMODE, iStartMode);
		myIntent.putExtra(Prefs.IT_P2P_STARTPARAM, flStartParam);
		myIntent.putExtra(Prefs.IT_P2P_STOPMODE, iStopMode);
		myIntent.putExtra(Prefs.IT_P2P_STOPPARAM, flStopParam);
		{
			int rgArray[] = new int[lstSelectedPIDs.size()];
			for(int x = 0;x < rgArray.length; x++) rgArray[x] = lstSelectedPIDs.get(x).intValue();
			myIntent.putExtra(Prefs.IT_SELECTEDPIDS_ARRAY, rgArray);
		}
		
		{
			myIntent.putExtra(Prefs.IT_IOIOANALPINS_ARRAY, rgAnalPins);
			myIntent.putExtra(Prefs.IT_IOIOPULSEPINS_ARRAY, rgPulsePins);
		}
		
		return myIntent;
    }
    @Override
    public void onDestroy()
    {
    	super.onDestroy();
    	synchronized(ApiDemos.class)
    	{
    		m_me = null;
    	}
    	ShutdownLappingMode();
    }
    @Override
    public void onPause()
    {
    	super.onPause();
    	
    	Notification notification = new Notification(R.drawable.icon, "Wifilapper is still recording data", System.currentTimeMillis());
    	// we need to put up a little notification at the top to tell them we're still running.
    	// we need to set a bunch of persisted settings so that the next LandingScreen activity knows to hop to this 
    	Intent toLaunch = new Intent(getApplicationContext(),ApiDemos.class);
    	
		toLaunch.setAction("android.intent.action.MAIN");
		
		toLaunch.addCategory("android.intent.category.LAUNCHER");           
		
		
		PendingIntent intentBack = PendingIntent.getActivity(getApplicationContext(),   0,toLaunch, PendingIntent.FLAG_UPDATE_CURRENT);
		
		notification.setLatestEventInfo(getApplicationContext(),"WifiLapper is still running", "Click here to open the app.  You can use menu->quit to exit.", intentBack);
        
		NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		mNotificationManager.notify(RESTART_NOTIFICATION_ID, notification);
		
		m_restartIntent = intentBack;
    }
	public void onResume()
    {
    	super.onResume();
    }
    
    public static ApiDemos Get()
    {
    	synchronized(ApiDemos.class)
    	{
    		return m_me;
    	}
    }

	
    public void NotifyNewSMS(String strSMS, String strPhoneNumber)
    {
    	if(!m_fSupportSMS) return;
    	
    	final String strWifiIP = "setip";
    	final String strWifiSSID = "setssid";
    	
    	// someone has texted me.  How quaint
    	String strLowercase = strSMS.toLowerCase(Locale.US);
    	if(m_strPrivacyPrefix.length() <= 0 || strLowercase.startsWith(m_strPrivacyPrefix))
    	{
    		m_strMessage = strSMS.substring(4);
    		m_strMessagePhone = strPhoneNumber;
    		m_iMsgTime = (int)((System.currentTimeMillis()/1000) + 1*60); // show this message for a maximum of 4 minutes
    	}
    	else if(strLowercase.startsWith(strWifiIP))
    	{
    		String strIP = strLowercase.substring(strWifiIP.length());
    		m_lapSender.SetIP(strIP);
    	}
    	else if(strLowercase.startsWith(strWifiSSID))
    	{
    		String strSSID = strLowercase.substring(strWifiSSID.length());
    		m_lapSender.SetSSID(strSSID);
    	}
    	else
    	{
    		// not prefixed with privacy prefix, so ignore
    	}
    }
    
    @Override
    public boolean onCreateOptionsMenu(Menu menu) 
    {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.closemenu, menu);
    	return true;
    }

	public static void SaveSharedPrefs(SharedPreferences prefs, String strIP, String strSSID, String strGPS, String strRaceName)
	{
		Editor edit = prefs.edit();
		if(strIP != null) edit = edit.putString(Prefs.PREF_IP_STRING, strIP);
		if(strGPS != null) edit = edit.putString(Prefs.PREF_BTGPSNAME_STRING, strGPS);
		if(strSSID != null) edit = edit.putString(Prefs.PREF_SSID_STRING, strSSID);
		if(strRaceName != null) edit = edit.putString(Prefs.PREF_RACENAME_STRING,strRaceName);
		edit.commit();
	}
    private void ShutdownLappingMode()
    {
    	if(m_lapSender != null)
		{
			m_lapSender.Shutdown();
			m_lapSender = null;
		}
		if(this.m_thdFakeLocs != null)
		{
			m_thdFakeLocs.Shutdown();
			m_thdFakeLocs = null;
		}
		if(this.m_msgMan != null)
		{
			m_msgMan.Shutdown();
			m_msgMan = null;
		}
		
		if( this.m_wifiScanThd != null )
		{
			ShutdownScan();
			m_wifiScanThd = null;
		}
		
		closeLogFile();
		
		SensorManager sensorMan = (SensorManager)getSystemService(SENSOR_SERVICE);
	    if(sensorMan != null)
	    {
	    	sensorMan.unregisterListener(this);
	    }
	    if(m_restartIntent != null)
	    {
	    	m_restartIntent.cancel();
	    }
		NotificationManager mNotificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		mNotificationManager.cancel(RESTART_NOTIFICATION_ID);
		if(m_obd != null)
		{
			m_obd.Shutdown();
		}
		if(m_btgps != null)
		{
			m_btgps.Shutdown();
		}
		if(m_ioio != null)
		{
			m_ioio.Shutdown();
		}
		
		if( m_myLaps != null )
		{
			m_myLaps.Prune();
			m_myLaps = null;
		}
		LocationManager locMan = (LocationManager)getApplicationContext().getSystemService(Context.LOCATION_SERVICE);
		if(locMan != null)
		{
			locMan.removeUpdates(this);
		}

		finish();
    }
    
    @Override
    public boolean onOptionsItemSelected(MenuItem menu)
    {
    	if(menu.getItemId() == R.id.mnuClose)
    	{
    		ShutdownLappingMode();
    		return true;
    	}
    	else if(menu.getItemId() == R.id.mnuBuyFull)
    	{
    		Intent intent = new Intent(Intent.ACTION_VIEW);
    		intent.setData(Uri.parse("market://details?id=com.artsoft.wifilapperfull"));
    		startActivity(intent);
    	}
    	return super.onOptionsItemSelected(menu);
    }
    @Override
    public void onConfigurationChanged(Configuration con)
    {
    	super.onConfigurationChanged(con);
    }
    // only called once the user has done all their settings stuff
    private void StartupTracking(boolean fRequireWifi, boolean fUseIOIO, IOIOManager.PinParams rgAnalPins[], IOIOManager.PinParams rgPulsePins[], int iButtonPin, int rgSelectedPIDs[], String strIP, String strSSID, String strBTGPS, String strBTOBD2, boolean fUseAccel, boolean fUseAccelCorrection, int iFiltertype, float flPitch, float flRoll, boolean fTestMode, int idLapLoadMode)
    {
    	ApiDemos.State eEndState = ApiDemos.State.WAITING_FOR_GPS;

    	m_mapPIDS = new HashMap<Integer,DataChannel>();
    	m_mapPins = new HashMap<Integer,DataChannel>();

	    if(m_lapParams.IsValid(m_fUseP2P))
    	{
	    	if(m_fUseP2P)
	    	{
	    		m_startDecider = new LineSplitDecider(m_lapParams.lnStart, m_lapParams.vStart);
	    		m_stopDecider = new LineSplitDecider(m_lapParams.lnStop, m_lapParams.vStop);
	    	}
	    	else
	    	{
    			m_startDecider = m_stopDecider = new LineSplitDecider(m_lapParams.lnStart, m_lapParams.vStart);
	    	}
    		eEndState = State.WAITING_FOR_GPS;
    	}
    	else
    	{
    		// didn't include start-finish lines, so we have to start from the beginning
    		eEndState = State.WAITING_FOR_GPS;
    		SetupSplitDeciders(); // we'll need the automatic split deciders since splits were not included
    	}
	    
	    if(fUseIOIO && (rgAnalPins.length > 0 || rgPulsePins.length > 0) )
	    {
	    	m_ioio = new IOIOManager(this, this, rgAnalPins,rgPulsePins, iButtonPin);
	    }
	    else
	    {
	    	SetState(IOIOManager.class,STATE.OFF,"IOIO not selected");
	    }
	    
		WifiManager pWifi = (WifiManager)getSystemService(Context.WIFI_SERVICE);

	    m_lapSender = new LapSender(this, this, pWifi, strIP, strSSID, fRequireWifi);
	    m_msgMan = new MessageMan(this);
	    
	    if(m_lRaceId != -1 && m_lapParams.IsValid(m_fUseP2P))
	    {
	    	// they're resuming an old race
	    	m_lapSender.SetRaceId(m_lRaceId);
	    	LapAccumulator storedBestLap = RaceDatabase.GetBestLap(RaceDatabase.Get(), m_lapParams, m_lRaceId);
	    	if(storedBestLap != null)
	    	{
	    		m_best = storedBestLap;
	    		eEndState = State.LOADING;
	    		m_best.ThdDoDeferredLoad(this.m_pHandler, MSG_LOADING_PROGRESS, false);
	    	}
	    }
	    else if(m_lRaceId == -1 && m_lapParams.IsValid(m_fUseP2P))
	    {
	    	// they're running a new race with old splits
	    	m_lRaceId = RaceDatabase.CreateRaceIfNotExist(RaceDatabase.Get(), this.m_strRaceName, m_lapParams, fTestMode, m_fUseP2P, m_lapParams.iFinishCount);
	    	m_lapSender.SetRaceId(m_lRaceId);
	    }
	    
	    SetState(LocationManager.class,STATE.TROUBLE_GOOD,"Waiting for GPS...");
	    if(fTestMode)
	    {
	    	m_thdFakeLocs = new FakeLocationGenerator(m_pHandler,20);
	    }
	    else
	    {
	    	boolean fGPSSetup = false;
	    	if(strBTGPS.length() > 0)
	    	{
	    		// use our custom BT parser
                SharedPreferences settings = getSharedPreferences(Prefs.SHAREDPREF_NAME, 0);
        		boolean bBtInsecure = settings.getBoolean(Prefs.PREF_BTINSECURE_BOOL, Prefs.DEFAULT_BTINSECURE_BOOL);

	    		m_btgps = new BluetoothGPS(strBTGPS, this, bBtInsecure);
	    		fGPSSetup = m_btgps != null && m_btgps.IsValid();
	    	}
	    	if(m_btgps == null || !m_btgps.IsValid())
	    	{
			    LocationManager locMan = (LocationManager)getApplicationContext().getSystemService(Context.LOCATION_SERVICE);
			    if(locMan != null && locMan.isProviderEnabled(LocationManager.GPS_PROVIDER))
			    {
			    	try
			    	{
			    		locMan.requestLocationUpdates(LocationManager.GPS_PROVIDER, 100, 0.0f, (LocationListener)this);
			    		fGPSSetup = true;
			    	}
			    	catch(Exception e)
			    	{
			    		System.out.println("Failure: " + e.toString());
			    	}
			    }
	    	}
	    	if(!fGPSSetup)
	    	{
	    	    SetState(LocationManager.class,STATE.TROUBLE_BAD,"Failed to initialize GPS.  Is your device's GPS enabled or your external GPS on?");
	    	}
	    }
	    
	    SensorManager sensorMan = (SensorManager)getSystemService(SENSOR_SERVICE);
	    if(sensorMan != null)
	    {
	    	if(fUseAccel)
	    	{
		    	Sensor accel = sensorMan.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
		    	if(accel != null)
		    	{
		    		sensorMan.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME);
		    		                                                     // _fastest is 10ms, _game is 20ms, 
		    		                                                     // _ui is 100ms, _normal is 200ms, on my phone
		    	}
	    	}
	    }
	    
	    m_dLastSpeed = 0;
	    m_ptLast = null;
	    m_ptCurrent = null;
	    
	    if(strBTOBD2 != null && strBTOBD2.length() > 0 && rgSelectedPIDs != null && rgSelectedPIDs.length> 0)
	    {
	    	m_obd = new OBDThread(this, this, strBTOBD2, rgSelectedPIDs);
	    }
	    else
	    {
	    	SetState(OBDThread.class, STATE.OFF, "Not using OBD2, invalid device selected, or no parameters selected.");
	    }
	    
	    this.SetState(eEndState);
    }
    
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) 
    {
        //Handle the back button
        if(keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_HOME) 
        {
            //Ask the user if they want to quit
            new AlertDialog.Builder(new ContextThemeWrapper(this, android.R.style.Theme_Dialog))
            .setIcon(android.R.drawable.ic_dialog_alert)
            .setTitle(R.string.quit)
            .setMessage(R.string.really_quit)
            .setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() 
            {
                @Override
                public void onClick(DialogInterface dialog, int which) 
                {
                    // Stop the activity
                    ApiDemos.this.ShutdownLappingMode();

                    // Clear the 'in progress' cookie, since it's a graceful shutdown
                    SharedPreferences settings = getSharedPreferences(Prefs.SHAREDPREF_NAME, 0);
            		Editor edit = settings.edit();
            		edit = edit.putBoolean(Prefs.PREF_RACE_IN_PROGRESS, false);
            		edit.commit();
                }

            }).setNegativeButton(R.string.no, null).show();

            return true;
        }
        else {
            return super.onKeyDown(keyCode, event);
        }

    }
    
    // click stuff
    @Override
    public void onClick(View v)
    {
    	if(m_eState == State.WAITINGFORSTART)
    	{
    		if(IsReadyForLineSet())
			{
    			m_startDecider.Tap();
    			if(m_startDecider.IsReady())
    			{
    				m_lapParams.lnStart = m_startDecider.GetLine();
    				m_lapParams.vStart = m_startDecider.GetDir();
    				if(m_stopDecider.IsReady())
    				{
    					m_lapParams.lnStop = m_stopDecider.GetLine();
    					m_lapParams.vStop = m_stopDecider.GetDir();
    					TrackLastLap(m_myLaps,false,false);
    				}
    				SetState(State.WAITINGFORSTOP);
    			}
			}
    	}
    	else if(m_eState == State.WAITINGFORSTOP)
    	{
    		if(IsReadyForLineSet())
    		{
    			m_stopDecider.Tap();
    			if(m_stopDecider.IsReady())
    			{
    				m_lapParams.lnStop = m_stopDecider.GetLine();
    				m_lapParams.vStop = m_stopDecider.GetDir();
    				TrackLastLap(m_myLaps,false,false);
    				SetState(State.MOVING_TO_STARTLINE);
    			}
    		}
    	}

    	// if they tap the screen without a message to acknowledge, clear the best lap.
    	// Otherwise, just the message is ack'd
    	if( !AcknowledgeMessage() && m_eState == State.PLOTTING ) {
    		m_best = null;
    	}
    	
    }
    public String[] GetDeciderWaitingStrings()
    {
    	if(!m_startDecider.IsReady())
    	{
    		return m_startDecider.GetUnReadyStrings();
    	}
    	else if(!m_stopDecider.IsReady())
    	{
    		return m_stopDecider.GetUnReadyStrings();
    	}
    	else
    	{
    		String str[] = new String[2];
    		str[0] = "Bugs";
    		str[1] = "Complain at wifilapper.freeforums.org";
    		return str;
    	}
    }
    private void SetupSplitDeciders()
    {
    	if(m_fUseP2P)
    	{
    		// they're doing a point-to-point, so we need to be careful about how we set up the start and finish deciders...
    		switch(m_iP2PStartMode)
    		{
    		case Prefs.P2P_STARTMODE_ACCEL:
    			//m_startDecider = new AccelSplitDecider();
    			break;
    		case Prefs.P2P_STARTMODE_SCREEN:
    			m_startDecider = new LineSplitDecider("Tap to set start line");
    			break;
    		case Prefs.P2P_STARTMODE_SPEED:
    			m_startDecider = new SpeedSplitDecider(false, false, m_flP2PStartParam, m_eUnitSystem, "Start");
    			break;
    		}
    		switch(m_iP2PStopMode)
    		{
    		case Prefs.P2P_STOPMODE_DISTANCE:
    			m_stopDecider = new DistanceSplitDecider(m_flP2PStopParam, m_eUnitSystem);
    			break;
    		case Prefs.P2P_STOPMODE_SCREEN:
    			m_stopDecider = new LineSplitDecider("Tap to set finish line");
    			break;
    		case Prefs.P2P_STOPMODE_SPEED:
    			m_stopDecider = new SpeedSplitDecider(true, true, m_flP2PStopParam, m_eUnitSystem, "Finish");
    			break;
    		}
    	}
    	else
    	{
    		// just doing laps, so just use the normal split points
    		this.m_startDecider = new LineSplitDecider("Tap to set start/finish line");
    		this.m_stopDecider = m_startDecider; // if they're lapping, the start and end lines are identical
    	}
    }
    
    private boolean AcknowledgeMessage()
    {
    	boolean bRetCode=false;
    	if(m_fSupportSMS && m_fAcknowledgeBySMS && m_strMessage != null && m_strMessagePhone != null)
    	{
    		final int maxLen = 20;
    		bRetCode = true;
    		String strEllipsis = m_strMessage.length() > maxLen ? "..." : "";
    		try
    		{
    			SMSReceiver.SMSAcknowledgeMessage("Driver acknowledged '" + m_strMessage.substring(0,Math.min(m_strMessage.length(),20)) + strEllipsis + "'", m_strMessagePhone);
    		}
    		catch(Exception e)
    		{
        		Toast.makeText(this, "Exception: " + e.toString(), Toast.LENGTH_SHORT).show();
    		}
    	}
    	// Even if no SMS, calling this routine will clear the message, returning true
    	if(m_strMessage != null)
    		bRetCode = true;
        	
    	this.m_strMessagePhone = null;
    	this.m_strMessage = null;
    	return bRetCode;
    }
    public boolean IsReadyForLineSet()
    {
    	return m_ptCurrent != null && m_ptLast != null && m_dLastSpeed > 0.5;
    }
    private void TrackLastLap(LapAccumulator lap, boolean fTransmit, boolean fSaveAsLastLap)
    {
    	if(lap == null) return;
    	
    	lap.ForceFinish();
		if(m_lRaceId < 0 && m_lapParams.IsValid(m_fUseP2P))
		{
			m_lRaceId = RaceDatabase.CreateRaceIfNotExist(RaceDatabase.Get(), this.m_strRaceName, m_lapParams, this.m_fTestMode, m_fUseP2P, this.m_lapParams.iFinishCount);
			m_lapSender.SetRaceId(m_lRaceId);
		}
    	m_tmLastLap = System.currentTimeMillis();
    	if(m_lapSender != null)
    	{
    		if(fSaveAsLastLap)
    		{
    			m_lastLap = lap.CreateCopy(false,false);

    			if(m_best == null || lap.GetLapTime() < m_best.GetLapTime())
    			{
    				m_best = lap.CreateCopy(true,false);
    			}
    		}
    		if(fTransmit)
    		{
    			m_lapSender.SendLap(lap);
    		}
    	}
		if( m_best != null )
			m_best.ResetSearchPoint(); // start searching from the beginning of the best lap
    }

	@Override
	public void onAccuracyChanged(Sensor arg0, int arg1) 
	{
		// don't care!
	}

	long profile[] = new long[500];
	int iProfileIndex = 0;
	int profileLoops = 0;

	// http://www.arc.id.au/FilterDesign.html
	static float flFilter[] = { // Kaiser window, 3Hz, 17 points, 21dB
	0.00422721f / SensorManager.GRAVITY_EARTH,
			0.018569377f / SensorManager.GRAVITY_EARTH,
			0.034649219f / SensorManager.GRAVITY_EARTH,
			0.051321565f / SensorManager.GRAVITY_EARTH,
			0.067320032f / SensorManager.GRAVITY_EARTH,
			0.081378238f / SensorManager.GRAVITY_EARTH,
			0.092350166f / SensorManager.GRAVITY_EARTH,
			0.099325443f / SensorManager.GRAVITY_EARTH,
			0.1017175f / SensorManager.GRAVITY_EARTH,
			0.099325443f / SensorManager.GRAVITY_EARTH,
			0.092350166f / SensorManager.GRAVITY_EARTH,
			0.081378238f / SensorManager.GRAVITY_EARTH,
			0.067320032f / SensorManager.GRAVITY_EARTH,
			0.051321565f / SensorManager.GRAVITY_EARTH,
			0.034649219f / SensorManager.GRAVITY_EARTH,
			0.018569377f / SensorManager.GRAVITY_EARTH,
			0.00422721f / SensorManager.GRAVITY_EARTH };
	static int iFilterLength = flFilter.length; // must be odd
	static int iCurveLength = (iFilterLength + 1) / 2;

	float flSample[][] = new float[3][iFilterLength]; // Matrix of samples (axis
														// x history)
	int iSensorSampleIndex = 0;

	/*
	 * // 2Hz, 25pts, 50Hz, 22dB
	 * 
	 * static float flFilter2[] = { // length is iCurveLength .06915713f,
	 * .068381706f, .066090011f, .062379731f, .057411656f, .051396715f,
	 * .044585602f, .03725581f, .029696936f, .022196845f, .015024387f,
	 * .008419881f, .002582154f }; // 1Hz, 25pts, 50Hz, 28dB static float
	 * flFilter1[] = { // length is iCurveLength .055498669f, .055119891f,
	 * .053993268f, .05215765f, .049669922f, .04660917f, .04307113f,
	 * .039165411f, .035004398f, .030710189f, .02639933f, .022184206f,
	 * .018166102f };
	 */

	static int iSubmitPeriod = 8; // submit once per this many accel triggers 8
									// gives 8*20=160ms sample period
	int iTriggerCount = iSubmitPeriod;

	@Override
	public void onSensorChanged(SensorEvent event) {
		if (m_myLaps == null || m_eState != State.PLOTTING )
			return;

		long lTimeMs = event.timestamp / 1000000;
		int iTimeSinceAppStart = (int) (lTimeMs - this.m_tmAppStartUptime);
		int iAdjusted = 0; // fake the time to compensate some filter delay

		// // Profiling start
		// profile[iProfileIndex] = System.nanoTime();

		if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
			if (m_XAccel == null || !m_XAccel.IsParent(m_myLaps))
				m_XAccel = new DataChannel(DataChannel.CHANNEL_ACCEL_X,
						m_myLaps);
			if (m_YAccel == null || !m_YAccel.IsParent(m_myLaps))
				m_YAccel = new DataChannel(DataChannel.CHANNEL_ACCEL_Y,
						m_myLaps);
			if (m_ZAccel == null || !m_ZAccel.IsParent(m_myLaps))
				m_ZAccel = new DataChannel(DataChannel.CHANNEL_ACCEL_Z,
						m_myLaps);

			if (m_XAccel == null || m_YAccel == null || m_ZAccel == null)
				return;

			// Correct for offsets in the sensors
			for( int i=0; i<3; i++ )
				flSample[i][iSensorSampleIndex] = event.values[i] + flSensorOffset[i];
			if( --iTriggerCount==0 ) { 
				iTriggerCount= iSubmitPeriod;

				// time to submit a filtered sample
				float[] flAccum = new float[3];

				int index = iSensorSampleIndex;

				switch (eFilterType) {
				case KAISER: // AKA Kaiser
					// find the middle sample
					int iMidIndex;
					iMidIndex = iSensorSampleIndex + iCurveLength - 1;
					if (iMidIndex >= iFilterLength) // wrap
						iMidIndex -= iFilterLength;

					// Apply digital filter
					for (int i = 0; i < 3; i++) {
						flAccum[i] = flSample[i][iMidIndex] * flFilter[0]; // use middle filter coeff
						int iLeft = iMidIndex;
						int iRight = iMidIndex;
						for (int j = 1; j < iCurveLength; j++) { // traverse half the curve, exploiting symmetry
							iRight++;
							if (iRight > iFilterLength - 1) // wrap
								iRight = 0;
							iLeft--;
							if (iLeft < 0) // wrap
								iLeft = iFilterLength - 1;
							flAccum[i] += (flSample[i][iLeft] + flSample[i][iRight])
									* flFilter[j];
						}
					}
					iAdjusted = iTimeSinceAppStart - (iCurveLength - 1) * 20;
					break;

				case MOVING_SHORT: // AKA 8-point Moving Average
					for (int i = 0; i < 3; i++) {
						for (int j = 0; j < 8; j++) {
							flAccum[i] += flSample[i][index];
							if (--index < 0)
								index = iFilterLength - 1;
						}
						flAccum[i] /= (8 * SensorManager.GRAVITY_EARTH);
					}
					iAdjusted = iTimeSinceAppStart - 20 * 7 / 2;
					break;

				case MOVING_LONG: // AKA Moving Average, length is iFilterLength
					for (int i = 0; i < 3; i++) {
						for (int j = 0; j < iFilterLength; j++) {
							flAccum[i] += flSample[i][index];
							if (--index < 0)
								index = iFilterLength - 1;
						}
						flAccum[i] /= (iFilterLength * SensorManager.GRAVITY_EARTH);
					}
					iAdjusted = iTimeSinceAppStart - 20 * iFilterLength / 2;
					break;

				default: // or NONE
					// Just submit the current sample
					for (int i = 0; i < 3; i++) {
						flAccum[i] = (event.values[i] + flSensorOffset[i])
								/ SensorManager.GRAVITY_EARTH;
					}
					iAdjusted = iTimeSinceAppStart;
					break;
				}

				// Correct for the angle of the car mount, if requested
				float[] flCorrected = new float[3]; // acceleration data, after
													// shifting and rotating
				if (fUseAccelCorrection) {
					// this math block is about 35 us on my LG 2X
					float flSinTheta, flCosTheta;
					flSinTheta = (float) Math.sin(Math.toRadians(-flRoll));
					flCosTheta = (float) Math.cos(Math.toRadians(-flRoll));

					flCorrected[0] = flAccum[0] * flCosTheta - flAccum[2] * flSinTheta;
					flCorrected[2] = flAccum[0] * flSinTheta + flAccum[2] * flCosTheta;

					flSinTheta = (float) Math.sin(Math.toRadians(-flPitch));
					flCosTheta = (float) Math.cos(Math.toRadians(-flPitch));

					flCorrected[1] = flAccum[1] * flCosTheta - flCorrected[2] * flSinTheta;
					flCorrected[2] = flAccum[1] * flSinTheta + flCorrected[2] * flCosTheta;
				} else
					flCorrected = flAccum.clone();

				// Axis mapping: axis labels vs sensor event indicies:
				// in this program, x is the left/right force, y is the
				// accel/deaccel force. z is gravity
				// right turns are -x, braking is +y

				if (bPortraitDisplay) {
					// remap the axes, depending on orientation
					xAccelCum = -flCorrected[0];
					yAccelCum = flCorrected[2];
					zAccelCum = flCorrected[1];
				} else {
					xAccelCum = flCorrected[1];
					yAccelCum = flCorrected[2];
					zAccelCum = flCorrected[0];
				}

				// These adds take about 350us on average
				m_XAccel.AddData(xAccelCum, iAdjusted);
				m_YAccel.AddData(yAccelCum, iAdjusted);
				m_ZAccel.AddData(zAccelCum, iAdjusted);

			}

			// increment the sample index, with wrap
			if (++iSensorSampleIndex == iFilterLength)
				iSensorSampleIndex = 0;

			// // End profiling block
			// profile[iProfileIndex] = System.nanoTime() -
			// profile[iProfileIndex];
			// if( ++iProfileIndex == 500 ) {
			// iProfileIndex = 0;
			// profileLoops++;
			// }
			// if( profileLoops==2 ){
			// long min=99999999, max=0;
			// double average=0;
			// for( int i=0; i<500; i++ ) {
			// if( min > profile[i] )
			// min = profile[i];
			// if( max < profile[i] )
			// max = profile[i];
			// average += profile[i];
			// }
			// average = average/500;
			// profileLoops = 0;
			// }

		} // end if accelerometer
	}

	// location stuff
	@Override
    public void onLocationChanged(Location location) 
    {
    	SetState(LocationManager.class, STATE.ON, "GPS Working (" + (int)(GetGPSRate()+0.5) + "hz)");
    	if(m_tmAppStartTime == 0)
    	{
    		m_tmAppStartTime = location.getTime();
    		m_tmAppStartUptime = System.nanoTime() / 1000000;
    	}
    	location.setTime(location.getTime() - m_tmAppStartTime);

    	float dNow;
		
		dNow = location.getTime() / 1000.0f;
		
		if(m_ptCurrent != null)
		{
	    	float rgDistance[] = new float[1];
	    	Location.distanceBetween(location.getLatitude(), location.getLongitude(), m_ptCurrent.GetY(), m_ptCurrent.GetX(), rgDistance);
    	
    		double tmChange = (location.getTime()/1000.0f) - m_tmCurrent; // time difference in seconds
    		double dSpeed = rgDistance[0] / tmChange; // location difference in meters
    		if(dSpeed > 100.0)
    		{
    			// speed is fairly unbelievable (360km/h).  Just skip doing anything
    			return;
    		}
    	}
    	
		int iUnixTime = (int)(System.currentTimeMillis() / 1000);
    	m_ptLast = m_ptCurrent;
    	m_tmLast = m_tmCurrent;
    	m_ptCurrent = new Point2D((float)location.getLongitude(), (float)location.getLatitude());
    	m_tmCurrent = dNow;
    
    	
    	if(this.m_fRecordReception && m_myLaps != null)
    	{
    		if(m_XReception == null || !m_XReception.IsParent(m_myLaps)) m_XReception = new DataChannel(DataChannel.CHANNEL_RECEPTION_X,m_myLaps);
    		if(m_YReception == null || !m_YReception.IsParent(m_myLaps)) m_YReception = new DataChannel(DataChannel.CHANNEL_RECEPTION_Y,m_myLaps);

    		int iTimeSinceAppStart = (int)((System.nanoTime()/1000000) - this.m_tmAppStartUptime);
    		
    		m_XReception.AddData((float)location.getLongitude(), iTimeSinceAppStart);
    		m_YReception.AddData((float)location.getLatitude(), iTimeSinceAppStart);
    	}
    	
    	m_dLastSpeed = location.getSpeed();
    	
    	if(m_pLastLocation != null && location != null)
    	{
    		// track the incoming GPS rate
    		long lGap = location.getTime() - m_pLastLocation.getTime();
    		rgLastGPSGaps[iLastGPSGap] = lGap;
    		iLastGPSGap++;
    		iLastGPSGap = iLastGPSGap % rgLastGPSGaps.length;
    		
    	}
    	
    	m_pLastLocation = location;
    	
        // Called when a new location is found by the network location provider.
    	if(m_eState == State.WAITING_FOR_GPS)
    	{
    		// if we get a location while waiting for GPS, then we're good to advance to setting start/finish lines
			SetState(State.WAITINGFORSTART);
    	}
    	else if(m_eState == State.WAITINGFORSTART || m_eState == State.WAITINGFORSTOP)
    	{
    		if(m_myLaps == null)
    		{
    			m_myLaps = new LapAccumulator(m_lapParams, m_ptCurrent, iUnixTime, -1, (int)location.getTime(), location.getSpeed());
    		}
	    	m_myLaps.AddPosition(m_ptCurrent, (int)location.getTime(), location.getSpeed());
	    	if(!m_startDecider.IsReady() && m_startDecider.NotifyPoint(m_myLaps, m_ptLast, m_ptCurrent, location.getSpeed()))
	    	{
	    		SetState(State.WAITINGFORSTOP);
	    		// start recording this lap
	    		m_lapParams.lnStart = m_startDecider.GetLine();
	    		m_lapParams.vStart = m_startDecider.GetDir();
	    		if(m_stopDecider.IsReady())
	    		{
	    			m_lapParams.lnStop = m_stopDecider.GetLine();
	    			m_lapParams.vStop = m_stopDecider.GetDir();
		    		TrackLastLap(m_myLaps, true, false);
	    			SetState(State.MOVING_TO_STARTLINE);
	    		}
	    		m_myLaps = new LapAccumulator(m_lapParams, m_ptCurrent, iUnixTime, -1, (int)location.getTime(), location.getSpeed());
	    	}
	    	else if(!m_stopDecider.IsReady() && m_stopDecider != m_startDecider)
	    	{
	    		if(m_stopDecider.NotifyPoint(m_myLaps, m_ptLast, m_ptCurrent, location.getSpeed()))
	    		{
		    		m_lapParams.lnStop = m_stopDecider.GetLine();
		    		m_lapParams.vStop = m_stopDecider.GetDir();
		    		TrackLastLap(m_myLaps, true, false);
	    			SetState(State.MOVING_TO_STARTLINE);
	    		}
	    	}
	    	else if(m_startDecider.IsReady() && m_stopDecider.IsReady())
	    	{
	    		SetState(State.MOVING_TO_STARTLINE);
	    	}
	    	m_currentView.invalidate();
    	}
    	else if(m_eState == State.MOVING_TO_STARTLINE)
    	{
    		if(m_myLaps == null)
    		{
    			m_myLaps = new LapAccumulator(m_lapParams, m_ptCurrent, iUnixTime, -1, (int)location.getTime(), location.getSpeed());
    		}
	    	m_myLaps.AddPosition(m_ptCurrent, (int)location.getTime(), location.getSpeed());
	    	if( m_myLaps.GetPositionCount() > iMaxPointsPerLap ) {
	    		// spent too long waiting for start line.  prune off memory
	    		Log.d("Pruning","Spent too long waiting for start line crossing (too many pts in lap)");
	    		m_myLaps.Prune();
	    		m_myLaps = new LapAccumulator(m_lapParams, m_ptCurrent, iUnixTime, -1, (int)location.getTime(), location.getSpeed());
	    	}
	    	if(this.m_startDecider.NotifyPoint(m_myLaps, m_ptLast, m_ptCurrent, location.getSpeed()))
	    	{
	    		m_myLaps.Prune();
	    		m_myLaps = new LapAccumulator(m_lapParams, m_ptCurrent, iUnixTime, -1, (int)location.getTime(), location.getSpeed());
	    		SetState(State.PLOTTING);
	    	}
    	}
    	else if(m_eState == State.PLOTTING)
    	{
    		if(m_myLaps == null)
    		{
    			m_myLaps = new LapAccumulator(m_lapParams, m_ptCurrent, iUnixTime, -1, (int)location.getTime(), location.getSpeed());
    		}
    		m_myLaps.AddPosition(m_ptCurrent, (int)location.getTime(), location.getSpeed());
    		if(m_myLaps.IsDoneLap())
    		{
    			TrackLastLap(m_myLaps, true, true);

    			if(!m_fUseP2P) // if we're lapping, just start the next lap now
    			{
    				m_myLaps = new LapAccumulator(m_lapParams, m_myLaps.GetFinishPoint(), iUnixTime, -1, m_myLaps.GetLastCrossTime(), location.getSpeed());
    				m_myLaps.AddPosition(m_ptCurrent, (int)location.getTime(), location.getSpeed());
    			}
    			else // if we're point-to-point, then start worrying about getting back to the start line
    			{
    				SetState(State.MOVING_TO_STARTLINE);
    			}
    		}
    		else
    		{
    			if( m_myLaps != null && m_best != null && 
    					(m_myLaps.GetAgeInMilliseconds()/1000 > 2*m_best.GetLapTime() )  ||
    					m_myLaps.GetPositionCount() > iMaxPointsPerLap ) 
    			{
    				Log.d("Pruning","Due to slow lap or too many points in the lap.");
    				TrackLastLap(m_myLaps, true, false);
    				m_tmLastLap = 0; // This prevents a goofy 'last lap' display while moving to startline
    				SetState(State.MOVING_TO_STARTLINE);
    			}
    		}
    		m_currentView.invalidate();
    	}
    }

//	@TargetApi(Build.VERSION_CODES.HONEYCOMB)
//	public void DisableHWAcceleration(View view)
//    {
//    	// Disable HW acceleration for API11 and above, since this app doesn't work with it
//    	if (false && Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) // api11
//    		view.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
//    }
//    
    public double GetGPSRate()
    {
    	// returns the rate in hz of the last (rgLastGPSGaps.length) GPS signals
    	long lSumGap = 0;
    	for(int x = 0; x < rgLastGPSGaps.length; x++)
    	{
    		lSumGap += rgLastGPSGaps[x];
    	}
    	double dAvgGap = ((double)lSumGap / (double)rgLastGPSGaps.length) / 1000.0;
    	return 1 / dAvgGap;
    }
    
    @Override
    public void onStatusChanged(String provider, int status, Bundle extras) 
    {
    	boolean fStillWaiting = true;
    	if(provider == LocationManager.GPS_PROVIDER && status == LocationProvider.AVAILABLE && m_eState == State.WAITING_FOR_GPS)
    	{
    		SetState(LocationManager.class,STATE.ON,"Signal Acquired");
    		SetState(State.WAITINGFORSTART);
    	}
    	else if(provider == LocationManager.GPS_PROVIDER && status == LocationProvider.OUT_OF_SERVICE || status == LocationProvider.TEMPORARILY_UNAVAILABLE)
    	{
    		if(status == LocationProvider.OUT_OF_SERVICE)
    		{
    			SetState(LocationManager.class, STATE.TROUBLE_BAD, "No GPS Device.  Is your bluetooth device on?");
    		}
    		SetState(State.WAITING_FOR_GPS);
    	}
    	else if(provider.equals(BluetoothGPS.NO_VTG_FOUND))
    	{
    		Toast.makeText(this, "Your GPS device isn't transmitting velocity information.  Try the qstarz configuration info on www.wifilapper.com.", Toast.LENGTH_SHORT).show();
    	}
    	
    	if(fStillWaiting && status != LocationProvider.OUT_OF_SERVICE)
    	{
    		if(extras != null && extras.containsKey("satellites"))
    		{
    			int cSatellites = extras.getInt("satellites");
    			SetState(LocationManager.class,STATE.TROUBLE_GOOD,"Waiting for GPS (" + cSatellites + " satellites");
    		}
    		else
    		{
    			SetState(LocationManager.class,STATE.TROUBLE_GOOD,"Waiting for GPS");
    		}
    	}
    }
    @Override
    public void onProviderEnabled(String provider) 
    {
    }
    @Override
    public void onProviderDisabled(String provider) 
    {
    }
    
    // state stuff.
    // this will instantiate and set the proper view
    LapAccumulator GetCurrentLap() {return m_myLaps;}
    LapAccumulator GetLastLap() {return m_lastLap;}
    LapAccumulator GetBestLap() {return m_best;}
    
	public float GetTimeSinceLastSplit()
	{
		return ((float)(System.currentTimeMillis() - m_tmLastLap)) / 1000.0f;
	}
	public int GetLapSentCount()
	{
		if(m_lapSender != null)
		{
			return m_lapSender.GetLapSentCount();
		}
		else
		{
			return 0;
		}
	}
    void SetState(State eNewState)
    {
    	// first, let's do a state filter
    	if(eNewState == State.WAITINGFORSTART || eNewState == State.WAITINGFORSTOP)
    	{
    		if(m_startDecider.IsReady() && m_stopDecider.IsReady())
    		{
    			eNewState = State.MOVING_TO_STARTLINE;
    		}
    		else if(m_startDecider.IsReady() && !m_stopDecider.IsReady())
    		{
    			eNewState = State.WAITINGFORSTOP;
    		}
    		else if(!m_startDecider.IsReady())
    		{
    			eNewState = State.WAITINGFORSTART;
    		}
    	}
    	
    	m_currentView = null;
    	
    	switch(eNewState)
    	{
    	case LOADING:
    	{
    		View vLoading = View.inflate(this, R.layout.lapping_loading, null);
    		m_currentView = vLoading;
    		setContentView(vLoading);
    		vLoading.requestLayout();
    		break;
    	}
    	case WAITING_FOR_GPS:
    	{
    		View vGPS = View.inflate(this, R.layout.lapping_gpsview, null);
    		GPSWaitView vActualView = (GPSWaitView)vGPS.findViewById(R.id.gpsview);
    		m_currentView = vActualView;
    		setContentView(vGPS);
    		vGPS.requestLayout();
    		break;
    	}
    	case MOVING_TO_STARTLINE:
    	{
    		View vStartline = View.inflate(this, R.layout.lapping_movetostartline, null);
    		MoveToStartLineView vView = (MoveToStartLineView)vStartline.findViewById(R.id.movetostartline);
    		vView.DoInit(this);
    		m_currentView = vView;
    		vStartline.setOnClickListener(this);
    		setContentView(vStartline);
    		vStartline.requestLayout();
    		m_myLaps = null;
    		break;
    	}
    	case DEMOSCREEN:
    	{
    		View vDemo = View.inflate(this, R.layout.lapping_demoscreen, null);
    		Button btn = (Button)vDemo.findViewById(R.id.btnMarket);
    		if(btn != null)
    		{
    			btn.setOnClickListener(this);
    		}
    		m_currentView = vDemo;
    		setContentView(vDemo);
    		vDemo.requestLayout();
    		if(m_lapSender != null) m_lapSender.Shutdown();
    		if(m_btgps != null) m_btgps.Shutdown();
    		if(m_obd != null) {m_obd.Shutdown();}
    		
    		if(m_thdFakeLocs != null) m_thdFakeLocs.Shutdown();

    		LocationManager locMan = (LocationManager)getApplicationContext().getSystemService(Context.LOCATION_SERVICE);
    		if(locMan != null) locMan.removeUpdates(this);
    		m_myLaps = null;
			SetState(LocationManager.class, Utility.MultiStateObject.STATE.OFF, "GPS shut down.");
			
			
    		break;
    	}
    	case WAITINGFORSTART:
    	case WAITINGFORSTOP:
    	{
    		// we're waiting for the start and stop deciders to be satisfied and ready to go
    		View vLineDraw = View.inflate(this, R.layout.lapping_startfinishautodraw, null);
    		DeciderWaitingView vActualView = (DeciderWaitingView)vLineDraw.findViewById(R.id.linesetter);
    		vActualView.SetData(this);
    		vLineDraw.setOnClickListener(this);
    		m_currentView = vActualView;
    		setContentView(vLineDraw);
    		vLineDraw.requestLayout();
    		break;
    	}
    	case PLOTTING:
    		if( BuildConfig.DEBUG && !(this.m_lapParams != null && m_lapParams.IsValid(this.m_fUseP2P))) 
    		{
    			throw new AssertionError("Failed in Plotting state");
    		}

    		View vView = View.inflate(this, R.layout.lapping_laptimeview, null);
    		MapPaintView view = (MapPaintView)vView.findViewById(R.id.laptimeview);
    		view.SetData(this, m_strSpeedoStyle, m_eUnitSystem);
    		m_currentView = view;
    		vView.setOnClickListener(this);
    		setContentView(vView);
    		vView.requestLayout();
    		break;
    	}
    	if(m_statusBar != null) m_statusBar.DeInit();
    	
    	m_statusBar = (StatusBarView)findViewById(R.id.statusbar);
    	m_statusBar.SetStateData(this);
    	
    	m_eState = eNewState;
    	m_currentView.invalidate();
    }
    
	@Override
	public boolean handleMessage(Message msg) 
	{
		switch(msg.what)
		{
		case MSG_STATE_CHANGED:
			m_currentView.invalidate();
			m_statusBar.Refresh();
			return true;
		case MSG_FAKE_LOCATION:
			Location l = (Location)msg.obj;
			onLocationChanged(l);
			return true;
		case MSG_LOADING_PROGRESS:
			if(this.m_eState == State.LOADING)
			{
				if(msg.arg1 >= msg.arg2)
				{
					SetState(State.WAITING_FOR_GPS);
				}
				else
				{
					ProgressBar prog = (ProgressBar)m_currentView.findViewById(R.id.prgLoading);
					prog.setMax(msg.arg2);
					prog.setProgress(msg.arg1);
				}
			}
			return true;
		case MSG_IOIO_BUTTON:
			// the user pressed a digital input on their IOIO that we are going to treat as a click.  So call the click code.
			onClick(m_currentView);
			return true;
		}
		return false;
	}
	
	private interface SplitDecider
	{
		public abstract boolean NotifyPoint(LapAccumulator currentLap, Point2D ptLast, Point2D ptCurrent, float flCurrentVel);
		public abstract boolean IsReady();
		public abstract String[] GetUnReadyStrings(); // returns the string we display when this split decider isn't ready (example: "tap to set start line")
		public abstract void Tap(); // user tapped the screen.  absorb this input
		public abstract LineSeg GetLine();
		public abstract Vector2D GetDir();
	}
	private abstract static class BaseSplitDecider implements SplitDecider
	{
		protected BaseSplitDecider()
		{
		}
		@Override
		public boolean NotifyPoint(LapAccumulator currentLap, Point2D ptLast, Point2D ptCurrent, float flCurrentVel)
		{
			// handles the case that all NotifyPoints have to handle: did this point cross the line and direction we have set?
			this.currentLap = currentLap;
			
			if(IsReady() && ptCurrent != null && ptLast != null)
			{
				LineSeg lnPoints = new LineSeg(ptCurrent,ptLast);
				LineSeg.IntersectData data = new LineSeg.IntersectData();
				
				Vector2D vNewDir = Vector2D.P1MinusP2(ptCurrent, ptLast);
				if(lnPoints.Intersect(ln, data, true, true) && vNewDir.DotProduct(vCrossDir) > 0)
				{
					return true;
				}
			}
			return false;
		}
		public abstract String[] GetUnReadyStrings(); // returns the string we display when this split decider isn't ready (example: "tap to set start line")
		public void Tap() {}; // user tapped the screen.  absorb this input

		@Override
		public final boolean IsReady()	{return ln != null && vCrossDir != null && vCrossDir.GetLength() > 0 && ln.GetLength() > 0;	}
		@Override
		public final LineSeg GetLine() {return ln;}
		@Override
		public final Vector2D GetDir() {return vCrossDir;}

		protected LapAccumulator currentLap;
		protected LineSeg ln;
		protected Vector2D vCrossDir;
		
	}
	private static class SpeedSplitDecider extends BaseSplitDecider
	{
		SpeedSplitDecider(boolean fArmedByDefault, boolean fOnSlowDown, float flSpeed, Prefs.UNIT_SYSTEM eUnitSystem, String strStartFinish)
		{
			super();
			this.fArmed = fArmedByDefault;
			this.fOnSlowDown = fOnSlowDown;
			this.flCrossSpeed = flSpeed;
			this.eSystem = eUnitSystem;
			this.strStartFinish = strStartFinish;
		}
		@Override
		public boolean NotifyPoint(LapAccumulator currentLap, Point2D ptLast, Point2D ptCurrent, float flCurrentVel)
		{
			if(super.NotifyPoint(currentLap, ptLast, ptCurrent, flCurrentVel)) return true;
			
			if(!IsReady() && fArmed && flLastSpeed >= 0)
			{
				float flLastDiff = flLastSpeed - flCrossSpeed;
				float flThisDiff = flCurrentVel - flCrossSpeed;
				
				final boolean fCross = flLastDiff * flThisDiff <= 0; // did we cross the setpoint?
				final boolean fSlowDownCross = fCross && flThisDiff < 0; // did we cross the setpoint while decelerating?
				final boolean fSpeedUpCross = fCross && flThisDiff > 0; // did we cross the setpoint while accelerating?
				if(fSpeedUpCross && !fOnSlowDown || fSlowDownCross && fOnSlowDown)
				{
					// this means we have cross the targeted speed, and crossed it in the direction this SplitDecider has been looking for
					if(currentLap.GetPositionCount() > 3)
					{
						vCrossDir = new Vector2D(0,0); // allocate a vector2D so that MakeSplitAtIndex can edit it for us
						ln = currentLap.MakeSplitAtIndex(currentLap.GetPositionCount()-1, vCrossDir);
						return true;
					}
				}
				
			}
			flLastSpeed = flCurrentVel;
			return false;
		}
		@Override
		public String[] GetUnReadyStrings()
		{
			String str[] = new String[2];
			if(fArmed)
			{
				str[0] = strStartFinish + " will be set when speed";
				str[1] = "crosses " + Prefs.FormatMetersPerSecond(flCrossSpeed, null, eSystem, true) + " (current: " + Prefs.FormatMetersPerSecond(flLastSpeed, null, eSystem, false) + ")";
			}
			else
			{
				str[0] = "Tap screen to arm speed";
				str[1] = "";
			}
			return str;
		}
		public void Tap()
		{
			fArmed = true;
		}
		
		private boolean fArmed; // we aren't ready to go until we're armed
		private String strStartFinish;
		Prefs.UNIT_SYSTEM eSystem;
		private float flCrossSpeed;
		private float flLastSpeed = -1;
		private boolean fOnSlowDown; // whether we're looking for the vehicle slowing down (true) or speeding up (false)
	}

	private static class DistanceSplitDecider extends BaseSplitDecider
	{
		DistanceSplitDecider(float flNeededDistance, Prefs.UNIT_SYSTEM eUnitSystem)
		{
			super();
			
			this.flNeededDistance = flNeededDistance;
			this.eSystem = eUnitSystem;
		}
		@Override
		public boolean NotifyPoint(LapAccumulator currentLap, Point2D ptLast, Point2D ptCurrent, float flCurrentVel)
		{
			if(super.NotifyPoint(currentLap, ptLast, ptCurrent, flCurrentVel)) return true;
			
			if(!IsReady())
			{
				if(currentLap.GetDistanceMeters() > flNeededDistance)
				{
					// this means that we have just changed sign in our difference-from-target-speed.
					// therefore, we just crossed it.
					if(currentLap.GetPositionCount() > 3)
					{
						vCrossDir = new Vector2D(0,0); // allocate a vector2D so that MakeSplitAtIndex can edit it for us
						ln = currentLap.MakeSplitAtIndex(currentLap.GetPositionCount()-1, vCrossDir);
						return true;
					}
				}
				
			}
			return false;
		}
		@Override
		public String[] GetUnReadyStrings()
		{
			String str[] = new String[2];
			str[0] = "Finish will be set when distance";
			str[1] = "crosses " + Prefs.FormatDistance(flNeededDistance, null, eSystem, true) + " (current: " + Prefs.FormatDistance(currentLap.GetDistanceMeters(), null, eSystem, true) + ")";
			return str;
		}
		
		Prefs.UNIT_SYSTEM eSystem;
		private float flNeededDistance; // how far do we need to go (in meters)?
	}
	private static class LineSplitDecider extends BaseSplitDecider
	{
		LineSplitDecider(String strSetupLine)
		{
			super();
			this.strSetupLine = strSetupLine;
		}
		LineSplitDecider(LineSeg ln, Vector2D vDir)
		{
			super();
			this.ln = ln;
			this.vCrossDir = vDir;
		}
		@Override
		public boolean NotifyPoint(LapAccumulator currentLap, Point2D ptLast, Point2D ptCurrent, float flCurrentVel)
		{
			if(super.NotifyPoint(currentLap,ptLast,ptCurrent, flCurrentVel)) return true;
			
			LineSeg.IntersectData intersect = new LineSeg.IntersectData();
			LapAccumulator.CrossData cross = new LapAccumulator.CrossData();
			if(!IsReady() && currentLap.IsNowCrossingSelf(intersect,cross) && currentLap.GetFinishesNeeded() == 1)
			{
				if(cross.flSpeedOfCrossedLine > 5)
				{
					// the car crossed a line where it was going fast, while the car was going fast.  therefore, we should set start/finish lines
					// we need to fish out pairs of points so we can do proper start/finish setting
					vCrossDir = new Vector2D(0,0);
					ln = currentLap.MakeSplitAtIndex(cross.ixCrossedLine,vCrossDir);
					return true;
				}
			}
			return false;
		}
		@Override
		public String[] GetUnReadyStrings()
		{
			String str[] = new String[2];
			
			str[0] = strSetupLine;
			str[1] = "Tap screen to force start/finish and splits";
			
			return str;
		}
		
		@Override
		public void Tap()
		{
			vCrossDir = new Vector2D(0,0); // allocate a vector2D so that MakeSplitAtIndex can edit it for us
			ln = currentLap.MakeSplitAtIndex(currentLap.GetPositionCount()-1, vCrossDir);
		}

		private String strSetupLine;
	}
	
	private Location m_pLastLocation;
	public Location GetLastPosition()
	{ 
		return m_pLastLocation;
	}

	public static class FakeLocationGenerator extends Thread implements Runnable
	{
		private Handler m_listener;
		private int m_hz;
		private float m_goalSpeed;
		private boolean m_fShutdown = false;
		
		public FakeLocationGenerator(Handler h, int hz)
		{
			m_listener = h;
			m_hz = 15;//hz;
			m_goalSpeed = 15f;// 17*60.0f;
			start();
		}

		public void Shutdown() {
			m_fShutdown = true;
		}

		@Override
		public void run() {
			Thread.currentThread().setName("Fake location generator");

			long lPositionTime = System.currentTimeMillis(); // always gets incremented by just iTimeToSleep
			long lStartTime = lPositionTime;
			double dLastX = 0;
			double dLastY = 0;
			double Angle = 0;
						
			while (!m_fShutdown) {
				int iTimeToSleep = 1000 / m_hz;
				try {
					Thread.sleep(iTimeToSleep);
					Location loc = new Location("fake");
					
					lPositionTime += iTimeToSleep;

					long curTime = lPositionTime - lStartTime;
					if ( curTime % (60*60*1000) > (1.2*60*1000)) //( (lPositionTime - lStartTime) > 60*1000) && (lPositionTime - lStartTime) < 5*60*1000) 
						m_goalSpeed = m_goalSpeed + .005f*(10000f-m_goalSpeed);
					else
						m_goalSpeed = m_goalSpeed + .05f*(15f-m_goalSpeed);

					m_goalSpeed = 15f;
					m_goalSpeed += Math.random()-.5f;
					
					double dAngle = 0*(Math.random()-0.5f)/75 + ( 2 * Math.PI ) * iTimeToSleep / (m_goalSpeed*1000);
					double dX = Math.sin(Angle ) * 0.0003;
					double dY = Math.cos(Angle ) * 0.0003;
					Angle += dAngle;
					
					loc.setTime(System.currentTimeMillis());
					
					loc.setLatitude(dX);
					loc.setLongitude(dY);
					loc.setAltitude(0);
					
					double dChangeX = dX - dLastX;
					double dChangeY = dY - dLastY;
					//loc.setSpeed((float)Math.sqrt(System.currentTimeMillis() % 9000));
					loc.setSpeed(500/m_goalSpeed);
					loc.setSpeed((System.currentTimeMillis() % 9000)/300f+4);
					float flBearing = ((float)Math.atan2(dChangeX, dChangeY))*180.0f/3.14159f;
					flBearing = (float)(((int)flBearing)%360);
					loc.setBearing(flBearing);
					
					Message m = Message.obtain(m_listener, ApiDemos.MSG_FAKE_LOCATION, loc);
					m_listener.sendMessage(m);
					dLastX = dX;
					dLastY = dY;
					
				}
				catch(InterruptedException e)
				{
				}
			}
		}
	}

	@Override
	public void SetMessage(int iTime, String strMsg) 
	{
		m_strMessage = strMsg;
		m_iMsgTime = iTime;
	}
	public String GetMessage()
	{
		int iCurTime = (int)(System.currentTimeMillis() / 1000);
		if(m_iMsgTime > iCurTime)
		{
			// if the message time is in the future...
			return m_strMessage;
		}
		return null;
	}
	@Override
	public void NotifyOBDParameter(int pid, float value) 
	{
		if(m_myLaps == null || m_eState != State.PLOTTING ) return;
		
		int iTimeSinceAppStart = (int)((System.nanoTime()/1000000) - this.m_tmAppStartUptime);
		
		boolean fNeededNew = false;
		DataChannel chan = m_mapPIDS.get(Integer.valueOf(pid));
		if(chan == null || !chan.IsParent(m_myLaps))
		{
			fNeededNew = true;
			chan = new DataChannel(DataChannel.CHANNEL_PID_START + pid,m_myLaps);
		}
		chan.AddData((float)value, iTimeSinceAppStart);
		if(fNeededNew)
		{
			m_mapPIDS.put(Integer.valueOf(pid), chan);
		}
	}
	@Override
	public void NotifyIOIOValue(int pin, int iCustomType, float flValue, boolean bSubmit) 
	{
		if(m_myLaps == null || m_eState != State.PLOTTING) return;
		
		int iTimeSinceAppStart = (int)(System.nanoTime()/1000000 - this.m_tmAppStartUptime);
		
		boolean fNeededNew = false;
		DataChannel chan = this.m_mapPins.get(Integer.valueOf(pin) );
		if(chan == null || !chan.IsParent(m_myLaps))
		{
			fNeededNew = true;
			
			final int iDCType = iCustomType == 0 ? (DataChannel.CHANNEL_IOIO_START + pin) : iCustomType;
			chan = new DataChannel(iDCType,m_myLaps);
		}
		if( bSubmit ) {
			if( false && bDeferring[pin] ) // Beta-test the disabling of the extra submission 
				chan.AddData(flLastIOIOSubmissionValue[pin], iLastIOIOSubmissionTime[pin]);
			chan.AddData((float)flValue, iTimeSinceAppStart);
			//
			bDeferring[pin] = false;
		}
		else
			bDeferring[pin] = true;
		
		iLastIOIOSubmissionTime[pin] = iTimeSinceAppStart;
		flLastIOIOSubmissionValue[pin] = flValue;
		
		if(fNeededNew)
		{
			m_mapPins.put(Integer.valueOf(pin), chan);
		}
	}
	
	
	@SuppressWarnings("rawtypes")
	Map<Class,StateData> m_mapStateData;
	
	@SuppressWarnings("rawtypes")
	@Override
	public synchronized void SetState(Class c, STATE eState, String strDesc) 
	{
		if(m_mapStateData == null) m_mapStateData = new HashMap<Class,StateData>();
		m_mapStateData.put(c, new StateData(eState,strDesc));
		m_pHandler.sendEmptyMessage(MSG_STATE_CHANGED);
	}
	@SuppressWarnings("rawtypes")
	@Override
	public synchronized StateData GetState(Class c) 
	{
		if(m_mapStateData == null) return new StateData(STATE.OFF,null);
		StateData stateFromMap = m_mapStateData.get(c);
		if(stateFromMap != null) return stateFromMap;
		return new StateData(STATE.OFF,null);
	}
	@Override
	public void NotifyIOIOButton() 
	{
		this.m_pHandler.sendEmptyMessage(MSG_IOIO_BUTTON);
	}

	class TimeStamp
	{
		long lTimestamp;
		int status;
		public TimeStamp(long ts, int status ) 
		{
			this.lTimestamp = ts;
			this.status=status;
		}
		public String toString()
		{
			return new String(String.valueOf(lTimestamp) + "," + String.valueOf(status)+"\n");
		}
	}

	List<TimeStamp> lTimeStamps = new ArrayList<TimeStamp>();
	final long lStartTime = System.currentTimeMillis();
	CONNLEVEL lastLevel = CONNLEVEL.SEARCHING;
	@Override
	public void SetConnectionLevel(CONNLEVEL eLevel) 
	{
		this.m_fRecordReception = (eLevel == CONNLEVEL.CONNECTED) || (eLevel == CONNLEVEL.FULLYCONNECTED);
		if( eLevel != CONNLEVEL.FULLYCONNECTED) 
			bScanning = true;
		else
			bScanning = false;
		try {
			appendToLog(String.valueOf(System.currentTimeMillis()-lStartTime)+","+String.valueOf(bScanning) + "," + String.valueOf(eLevel) +  "\n");
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}

class GPSWaitView extends View
{
	Paint paintSettings;
	private Matrix myMatrix;
	private Rect myRect;
	
	public GPSWaitView(Context context)
	{
		super(context);
		DoInit();
	}
	public GPSWaitView(Context context, AttributeSet attrs)
	{
		super(context,attrs);
		DoInit();
	}
	public GPSWaitView(Context context, AttributeSet attrs, int defStyle)
	{
		super(context,attrs,defStyle);
		DoInit();
	}
	private void DoInit()
	{
		paintSettings = new Paint();
		paintSettings.setARGB(255,255,255,255);
		
		myMatrix = new Matrix();
		myRect = new Rect();
	}
	public void onDraw(Canvas canvas)
	{	
		myMatrix.reset();
		canvas.setMatrix(myMatrix);
		canvas.clipRect(getLeft(),getTop(),getRight(),getBottom(),Op.REPLACE);
		//canvas.scale(1.5f,1.5f);
		
		myRect.set(getLeft()+10,getTop()+10,getRight()-10,getBottom()-10);
		final String str = "Waiting for GPS";
		Utility.DrawFontInBox(canvas, str, paintSettings, myRect);
	}
}

class MoveToStartLineView extends View
{
	Paint paintLines;
	Paint paintTrack;
	Paint paintSmallText;
	ApiDemos myApp;
	NumberFormat num;
	private Matrix myMatrix;
	private Rect rcAll;
	private Rect rcLapTime;
	private Rect rcLapLabel;
	private Rect rcOnScreen;
	
	public MoveToStartLineView(Context context)
	{
		super(context);
	}
	public MoveToStartLineView(Context context, AttributeSet attrs)
	{
		super(context,attrs);
	}
	
	public void DoInit(ApiDemos app)
	{

		num = NumberFormat.getInstance();
		myApp = app;
		paintSmallText = new Paint();
		paintSmallText.setColor(Color.WHITE);
		paintSmallText.setTextSize(20);
		
		paintTrack = new Paint();
		paintTrack.setColor(Color.GRAY);
				
		paintLines = new Paint();
		paintLines.setColor(Color.RED);
		
		myMatrix = new Matrix();
		rcAll = new Rect();
		rcLapTime = new Rect();
		rcLapLabel = new Rect();
		rcOnScreen = new Rect();
		
	}
	public void onDraw(Canvas canvas)
	{
		myMatrix.reset();
		canvas.setMatrix(myMatrix);
		//canvas.scale(1.5f,1.5f);
		canvas.clipRect(getLeft(),getTop(),getRight(),getBottom(),Op.REPLACE);
		
		rcAll.set(getLeft(),getTop(),getRight(), getBottom());

		String strMsg = myApp.GetMessage();
		if(myApp.GetTimeSinceLastSplit() < 2.0)
		{
			// draw the last split
			num.setMaximumFractionDigits(2);
			num.setMinimumFractionDigits(2);
			
			paintSmallText.setARGB(255,255,255,255); // just use white if they're going for absolute times
			
			final int cxLabels = getWidth()/10;
			final int cxSplit = getRight() - cxLabels;
			
			rcLapTime.set(getLeft(),getTop(),cxSplit,getBottom());
			rcLapLabel.set(cxSplit, getTop(),getRight(),getBottom());
			// current lap has no splits, so we must have just finished a lap
			LapAccumulator lapLast = myApp.GetLastLap();
			if(lapLast != null)
			{
				final double dLastLap = lapLast.GetLapTime();
				String strLapText = Utility.FormatSeconds((float)dLastLap);
				Utility.DrawFontInBox(canvas, strLapText, paintSmallText, rcLapTime);
				Utility.DrawFontInBox(canvas, "Lap", paintSmallText, rcLapLabel);
			}
		}
		else if(strMsg != null)
		{
			Utility.DrawFontInBox(canvas, strMsg, paintSmallText, rcAll);
		}
		else
		{
			String str = "Proceed to start/finish";
			String str2 = "";
			
			LapAccumulator lap = myApp.GetCurrentLap();
			if(lap != null)
			{
				FloatRect rcInWorld = lap.GetBounds(false);
				rcOnScreen.set(getLeft(),getTop(),getRight(),getBottom());
				
				List<LineSeg> lstSF = lap.GetSplitPoints(true);
				if(lstSF != null && lstSF.size() > 0)
				{
					FloatRect rcSF = lstSF.get(0).GetBounds();
					rcInWorld = rcInWorld.Union(rcSF);
					float flSFX = rcSF.ExactCenterX();
					float flSFY = rcSF.ExactCenterY();
					
					float rg[] = new float[2];
					Location.distanceBetween(flSFY, flSFX, lap.GetLastPoint().pt.y, lap.GetLastPoint().pt.x, rg);
					str2 = " (" + (int)rg[0] + "m away)";
				}
				
				LapAccumulator.DrawLap(lap, false, rcInWorld, canvas, paintTrack, paintLines, rcOnScreen);
			}
			if( rcOnScreen.width() < rcOnScreen.height() ) {
				// Portrait screen.  Print on 2 lines
				rcOnScreen = Utility.GetNeededFontSize(str, paintSmallText, rcAll);
				rcLapLabel.set(rcAll.left,rcAll.top,rcAll.right,rcAll.top+rcAll.height()/2);
				rcOnScreen = Utility.Justify(rcOnScreen, rcLapLabel, Utility.BOXJUSTIFY.CENTER_BOTTOM);
				Utility.DrawFontInBoxFinal(canvas, str, paintSmallText.getTextSize(), paintSmallText, rcOnScreen, Utility.TEXTJUSTIFY.CENTER);
				rcLapLabel.set(rcAll.left,rcAll.top+rcAll.height()/2,rcAll.right,rcAll.top+rcAll.bottom);
				rcOnScreen = Utility.Justify(rcOnScreen, rcLapLabel, Utility.BOXJUSTIFY.CENTER_TOP);
				Utility.DrawFontInBoxFinal(canvas, str2, paintSmallText.getTextSize(), paintSmallText, rcOnScreen, Utility.TEXTJUSTIFY.CENTER);
			}
			else
				Utility.DrawFontInBox(canvas, str+str2, paintSmallText, rcAll);
		}
	}
}

//this is the view that sets the start/finish lines
class DeciderWaitingView extends View
{
	Paint paintText;
	Paint paintLines;
	Paint paintTrack;
	Paint paintSmallText;
	Paint paintAttention;
	ApiDemos myApp;
	
	private Matrix myMatrix;
	private Rect rcOnScreen;
	
	LapAccumulator lap;
	
	public DeciderWaitingView(Context context)
	{
		super(context);
		DoInit();
	}
	public DeciderWaitingView(Context context, AttributeSet attrs)
	{
		super(context,attrs);
		DoInit();
	}
	public DeciderWaitingView(Context context, AttributeSet attrs, int defStyle)
	{
		super(context,attrs,defStyle);
		DoInit();
	}
	void SetData(ApiDemos api)
	{
		myApp = api;
	}
	private void DoInit()
	{
		paintText = new Paint();
		paintText.setARGB(255,255,255,255);
		paintText.setTextSize(40);

		paintSmallText = new Paint();
		paintSmallText.setARGB(255,255,255,255);
		paintSmallText.setTextSize(20);
		
		paintLines = new Paint();
		paintLines.setARGB(255,255,0,0);
		
		paintTrack = new Paint();
		paintTrack.setARGB(255,255,255,255);
		
		paintAttention = new Paint();
		paintAttention.setARGB(255,255,0,255);
		
		myMatrix = new Matrix();
		rcOnScreen = new Rect();

	}
	public void onDraw(Canvas canvas)
	{
		myMatrix.reset();
		canvas.setMatrix(myMatrix);
		canvas.clipRect(getLeft(),getTop(),getRight(),getBottom(),Op.REPLACE);
		
		lap = myApp.GetCurrentLap();
		if(lap != null)
		{
			if(myApp.IsReadyForLineSet())
			{
				FloatRect rcInWorld = lap.GetBounds(false);
				rcOnScreen.set(getLeft(),getTop(),getRight(),getBottom());
				
				LapAccumulator.DrawLap(lap, false, rcInWorld, canvas, paintTrack, paintLines, rcOnScreen);
				
				String str[] = myApp.GetDeciderWaitingStrings();
				
				final int mid = getTop() + getHeight()/2;
				
				// Flash the screen for 0.5 second every 2, to alert driver to set start/finish line
				if( System.currentTimeMillis()%2048 < 512)
					canvas.drawPaint(paintAttention);
				
				rcOnScreen.set(getLeft(),getTop(),getRight(),mid);
				Utility.DrawFontInBox(canvas, str[0], paintSmallText, rcOnScreen);
			}
			else
			{
				final String str1 = "You must be moving to set";
				final String str2 = "start/finish and split points";
				final int mid = getTop() + getHeight()/2;
				rcOnScreen.set(getLeft(),getTop(),getRight(),mid);
				Utility.DrawFontInBox(canvas, str1, paintSmallText, rcOnScreen);
				rcOnScreen.set(getLeft(),mid,getRight(),getBottom());
				Utility.DrawFontInBox(canvas, str2, paintSmallText, rcOnScreen);
			}
		}
		//canvas.drawText("Wifi: " + pStateMan.GetState(), 50.0f, 110.0f, paintText);
		//canvas.drawText("GPS: " + (int)myApp.GetGPSRate() + "hz", 50.0f, 130.0f, paintText);
	}
	
}
class MapPaintView extends View
{
	OrientationEventListener myOrientationEventListener1 ;

	Paint paintText;
	Paint paintBigText;
	ApiDemos myApp;
	String strSpeedoStyle;
	NumberFormat num;
	Prefs.UNIT_SYSTEM eDisplayUnitSystem;
	float fontSize[] = new float[7];
	Rect rcFontBounds[] = new Rect[7];
	boolean fontInitialized = false;
	
	private Matrix myMatrix;
	private Rect rcAll;
	private Rect rcLapTime;
	private Rect rcLapLabel;
	private Rect rcBestTime;
	private Rect rcBestLabel;
	private Rect rcMain;
	private Rect rcSecondary;
	Paint p;
	Paint pDelta;
	Paint pRect;
	
	private Rect rcTimeDiff;
	private Rect rcUpperValue;
	private Rect rcUpperLabel;
	private Rect rcLowerValue;
	private Rect rcLowerLabel;
	
	private LapAccumulator lap;
	private LapAccumulator lapLast;
	private LapAccumulator lapBest;

	int cPaintCounts = 0;
	
	public void ResetScreen()
	{
		fontInitialized = false;
	}

	public MapPaintView(Context context)
	{
		super(context);
		DoInit();
	}
	public MapPaintView(Context context, AttributeSet attrs)
	{
		super(context,attrs);
		DoInit();
	}
	public MapPaintView(Context context, AttributeSet attrs, int defStyle)
	{
		super(context,attrs,defStyle);
		DoInit();
	}
	public void SetData(ApiDemos api, String strSpeedoStyle, Prefs.UNIT_SYSTEM eUnitSystem)
	{
		this.myApp = api;
		this.strSpeedoStyle = strSpeedoStyle;
		this.eDisplayUnitSystem = eUnitSystem;
	}
	private void DoInit()
	{
		num = NumberFormat.getInstance();
		paintText = new Paint();
		paintText.setARGB(255,255,255,255);
		
		paintBigText = new Paint();
		paintBigText.setTextSize(150);
		paintBigText.setARGB(255, 255, 255, 255);
		fontInitialized = false;
		
		myMatrix = new Matrix();
		rcAll = new Rect();
		rcLapTime = new Rect();
		rcLapLabel = new Rect();
		rcBestTime = new Rect();
		rcBestLabel = new Rect();
		rcMain = new Rect();
		rcSecondary = new Rect();
		p = new Paint();
		pDelta = new Paint();
		pRect = new Paint();

		rcTimeDiff = new Rect();
		rcUpperValue= new Rect();
		rcUpperLabel= new Rect();
		rcLowerValue= new Rect();
		rcLowerLabel= new Rect();


	}
	private void DrawSpeedDistance(Canvas canvas, Rect rcOnScreen, LapAccumulator lap, LapAccumulator lapBest)
	{
		Paint paintLap = new Paint();
		Paint paintSplits = new Paint();
		
		if(lap != null)
		{
			FloatRect rcWindow = lapBest != null ? lapBest.GetBounds(true) : lap.GetBounds(true);
			if(lap != null) rcWindow = rcWindow.Union(lap.GetBounds(true));
			if(lapBest != null && lap != null)
			{
				final float flTotalDistance = lapBest.GetDistance();
				final float flCurDistance = lap.GetDistance();
				
				// have the window show the previous 20% of the lap and the upcoming 5%
				rcWindow.left = flCurDistance - flTotalDistance*0.20f;
				rcWindow.right = flCurDistance + flTotalDistance*0.05f;
			}
			
			if(lapBest != null)
			{
				// draw the best lap in purple
				paintLap.setARGB(255, 255, 0, 255);
				paintLap.setStrokeWidth(6);
				paintSplits.setARGB(255, 255, 0, 0);
				LapAccumulator.DrawLap(lapBest, true, rcWindow, canvas, paintLap, paintSplits, new Rect(rcOnScreen));
			}
			if(lap != null)
			{
				// draw the current lap in a brighter color
				paintLap.setARGB(255, 255, 255, 255); // current lap in white if we're doing speed-distance
				paintLap.setStrokeWidth(6);
				paintSplits.setARGB(255, 255, 0, 0);
				LapAccumulator.DrawLap(lap, true, rcWindow, canvas, paintLap, paintSplits, new Rect(rcOnScreen));
			}
		}
	}
	private void DrawPlusMinus(Canvas canvas, Rect rcOnScreen, LapAccumulator lap, LapAccumulator lapBest)
	{
		if(lapBest == null)
		{
			DrawSpeedDistance(canvas,rcOnScreen,lap,lapBest);
		}
		else
		{
			final double flThisTime = ((double)lap.GetAgeInMilliseconds())/1000.0;
			if( flThisTime < 3 )
			{
				String strLap = "Lap";
				p.setARGB(255, 255, 255, 255);
				Utility.DrawFontInBox(canvas, strLap, p, rcOnScreen);
				return;
			}			
			final TimePoint2D ptCurrent = lap.GetLastPoint();
			final double flPercentage = ptCurrent.dDistance / lapBest.GetDistance();
			final double flBestTime = (double)(lapBest.GetTimeAtPosition(ptCurrent,flPercentage)/1000.0);
			num.setMaximumFractionDigits(1);
			num.setMinimumFractionDigits(1);
			
			// according to a phone call at 1:51pm on Sunday Jan 29, if you're ahead, it should be minus
			// it took us "flThisTime" seconds to get to the current distance
			// on the best lap, it took us "flLastTime"
			float flToPrint = (float)(flThisTime - flBestTime);
			String strPrefix;
			if(flToPrint < 0)
			{
				// the current lap is ahead...
				paintBigText.setARGB(255, 128, 255, 128);
				p.setARGB(255, 128, 255, 128);
				
				strPrefix = "";
			}
			else
			{
				paintBigText.setARGB(255, 255, 128, 128);
				p.setARGB(255, 255, 128, 128);
				strPrefix = "+";
			}
			String strText = num.format(flToPrint);
			
			String strToPaint = strPrefix + strText;
			Utility.DrawFontInBox(canvas, strToPaint, p, rcOnScreen);
		}
	}
	private void DrawPlusMinusNew(Canvas canvas, Rect rcOnScreen, LapAccumulator lap, LapAccumulator lapBest, Paint p)
	{
		if(lapBest == null)
		{
			DrawSpeedDistance(canvas,rcOnScreen,lap,lapBest);
		}
		else
		{

			final TimePoint2D ptCurrent = lap.GetLastPoint();
			final TimePoint2D ptBest = lapBest.myGetInterpolatedPointAtPosition(ptCurrent);

			final double flThisTime = ((double)lap.GetAgeInMilliseconds())/1000.0;
			if( flThisTime < 3 )
			{
				// Display the word Lap for 3 seconds as the line is crossed
				p.setColor(Color.WHITE);
				p.setStyle(Style.FILL);

				Utility.DrawFontInBoxFinal(canvas, "Lap", fontSize[1], p, rcFontBounds[1], Utility.TEXTJUSTIFY.CENTER);
				return;
			}			
						
			final float flBestTime = ptBest.iTime / 1000f;
			
			num.setMaximumFractionDigits(1);
			num.setMinimumFractionDigits(1);
			
			// according to a phone call at 1:51pm on Sunday Jan 29, if you're ahead, it should be minus
			// it took us "flThisTime" seconds to get to the current distance
			// on the best lap, it took us "flLastTime"
			float flToPrint = (float)(flThisTime - flBestTime);
			
			Rect rcDelta = new Rect(rcOnScreen);
			final float flDelta = flToPrint/1f;
			if( flToPrint > 0 ) {
				pRect.setColor(Color.RED);
				rcDelta.right = Math.min((int) (rcDelta.width() *flDelta), rcDelta.right);
			}
			else {
				pRect.setColor(Color.GREEN);
				rcDelta.left = Math.max((int) (rcDelta.width()+rcDelta.width() *flDelta), 0);				
			}
			canvas.drawRect(rcDelta, pRect);

			String strToPaint = num.format(Math.abs(flToPrint));

			p.setColor(Color.BLACK);
			p.setStyle(Style.FILL_AND_STROKE);
			switch( strToPaint.length() ) {
			case 3: 
			case 4:
				Utility.DrawFontInBoxFinal(canvas, strToPaint, fontSize[strToPaint.length()-3], p, rcFontBounds[strToPaint.length()-3], Utility.TEXTJUSTIFY.CENTER);
				p.setStyle(Style.FILL);			
				p.setColor(Color.WHITE);
				Utility.DrawFontInBoxFinal(canvas, strToPaint, fontSize[strToPaint.length()-3], p, rcFontBounds[strToPaint.length()-3], Utility.TEXTJUSTIFY.CENTER);
				break;

			default:
				Utility.DrawFontInBoxFinal(canvas, "99.9", fontSize[1], p, rcFontBounds[1], Utility.TEXTJUSTIFY.CENTER);
				p.setStyle(Style.FILL);			
				p.setColor(Color.WHITE);
				Utility.DrawFontInBoxFinal(canvas, "99.9", fontSize[1], p, rcFontBounds[1], Utility.TEXTJUSTIFY.CENTER);
				break;
			}
		}
	}
	private void DrawComparative(Canvas canvas, Rect rcOnScreen, LapAccumulator lap, LapAccumulator lapBest)
	{
		// this displays the driver's current speed, as well as their speed at that point for the best lap
		num.setMaximumFractionDigits(1);
		num.setMinimumFractionDigits(1);
		
		Rect rcTop = new Rect();
		Rect rcBottom = new Rect();
		final int cyTarget = rcOnScreen.bottom - rcOnScreen.top;
		rcTop.set(rcOnScreen.left,rcOnScreen.top,rcOnScreen.right,rcOnScreen.top + cyTarget/2);
		rcBottom.set(rcOnScreen.left, rcTop.bottom, rcOnScreen.right, rcOnScreen.bottom);
		Paint p = new Paint();
		p.setColor(Color.WHITE);
		if(lap != null)
		{	
			final TimePoint2D ptCurrent = lap.GetLastPoint();
			
			final float flSpeed = (float)ptCurrent.dVelocity;
			String strSpeed = Prefs.FormatMetersPerSecond(flSpeed,num,eDisplayUnitSystem,false);
			Utility.DrawFontInBox(canvas, strSpeed, p, rcTop);
		}
		if(lapBest != null)
		{
			final TimePoint2D ptCurrent = lap.GetLastPoint();
			final float flBestSpeed = (float)lapBest.GetSpeedAtPosition(ptCurrent);
			String strSpeed = Prefs.FormatMetersPerSecond(flBestSpeed,num,eDisplayUnitSystem,false);
			Utility.DrawFontInBox(canvas, strSpeed, p, rcBottom);
		}
	}
	private void DrawLapTimer(Canvas canvas, Rect rcOnScreen, LapAccumulator lap, LapAccumulator lapBest)
	{
		
		if( !fontInitialized ) {

			if( rcAll.width() < rcAll.height() )
			{
				// Portrait mode
				final int midSplit  = rcAll.top  + rcAll.height() * 3/5;
				final int lowSplit  = rcAll.top  + rcAll.height() * 4/5;
				final int rightSplit= rcAll.left + rcAll.width() * 4/5;

				rcTimeDiff.set(rcAll.left, rcAll.top, rcAll.right, midSplit);
				rcUpperValue.set(rcAll.left, midSplit, rightSplit, lowSplit);
				rcUpperLabel.set(rightSplit, midSplit, rcAll.right, lowSplit);
				rcLowerValue.set(rcAll.left, lowSplit, rightSplit, rcAll.bottom);
				rcLowerLabel.set(rightSplit, lowSplit, rcAll.right, rcAll.bottom);

			}
			else
			{
				// Landscape mode
				final int midXSplit  = rcAll.left + rcAll.width() * 3/5;
				final int midYSplit  = rcAll.centerY();
				final int labelSplit = rcAll.height()/8;

				rcTimeDiff.set(rcAll.left, rcAll.top, midXSplit, rcAll.bottom);
				rcUpperLabel.set(midXSplit, rcAll.top, rcAll.right, rcAll.top+labelSplit);
				rcUpperValue.set(midXSplit, rcAll.top+labelSplit, rcAll.right, midYSplit);
				rcLowerLabel.set(midXSplit, midYSplit, rcAll.right, midYSplit+labelSplit);
				rcLowerValue.set(midXSplit, midYSplit+labelSplit, rcAll.right, rcAll.bottom);

			}

			// Optionally inset the boxes
			//			rcTimeDiff.inset(20,20);
			//			rcUpperLabel.inset(10,0);
			//			rcUpperValue.inset(20,20);
			//			rcLowerLabel.inset(10,10);
			//			rcLowerValue.inset(20,20);
			pDelta.setStrokeWidth(rcTimeDiff.width()/20);
			pDelta.setTypeface(Typeface.DEFAULT_BOLD);

			p.setColor(Color.WHITE);

			// Optionally draw screen dividers
			//			Paint.Style style = p.getStyle();
			//			p.setStyle(Style.STROKE);
			//			canvas.drawRect(rcTimeDiff, p);
			//			canvas.drawRect(rcUpperLabel, p);
			//			canvas.drawRect(rcUpperValue, p);
			//			canvas.drawRect(rcLowerLabel, p);
			//			canvas.drawRect(rcLowerValue, p);
			//			p.setStyle(style);

			Rect rcResult = new Rect();

			// Optionally inset the boxes
			Rect rcTimeDiffInset = new Rect(rcTimeDiff);
			rcTimeDiffInset.inset(rcTimeDiff.width()/20,rcTimeDiff.height()/20);
			rcUpperLabel.inset(5,5);
			rcUpperValue.inset(5,5);
			rcLowerLabel.inset(5,5);
			rcLowerValue.inset(5,5);

			// First, calculate the font size required for the delta time, whether <10 sec or >=10sec
			fontSize[0]=9999;
			num.setMinimumIntegerDigits(1);
			num.setMinimumFractionDigits(1);
			for( float f = 0; f<10; f=f+1.1f) {
				rcResult = Utility.GetNeededFontSize(String.valueOf(num.format(f)), pDelta, rcTimeDiffInset);
				if( pDelta.getTextSize() < fontSize[0] ) {
					fontSize[0] = pDelta.getTextSize();
					rcFontBounds[0] = rcResult;
				}
			}
			rcFontBounds[0] = Utility.Justify(rcFontBounds[0], rcTimeDiffInset, Utility.BOXJUSTIFY.CENTER_CENTER);

			// now for >= 10sec
			fontSize[1]=9999;
			num.setMinimumIntegerDigits(2);
			for( float f = 0; f<100; f=f+11.1f) {
				rcResult = Utility.GetNeededFontSize(String.valueOf(num.format(f)), pDelta, rcTimeDiffInset);
				if( pDelta.getTextSize() < fontSize[1] ) {
					fontSize[1] = pDelta.getTextSize();
					rcFontBounds[1] = rcResult;
				}
			}
			rcFontBounds[1] = Utility.Justify(rcFontBounds[1], rcTimeDiffInset, Utility.BOXJUSTIFY.CENTER_CENTER);

			// This one is for the speed display
			num.setMinimumIntegerDigits(1);
			num.setMinimumFractionDigits(0);
			fontSize[2]=9999;

			for( float f = 111; f<1000; f=f+111f) {
				rcResult = Utility.GetNeededFontSize(String.valueOf(num.format(f)), p, rcUpperValue);
				if( p.getTextSize() < fontSize[2] ) {
					fontSize[2] = p.getTextSize();
					rcFontBounds[2] = rcResult;
				}
				
//				String str = new String();
//				str = String.valueOf(num.format(f));
//				float flMeas = p.measureText(str);
//				float fAscent = p.ascent();
//				float fDescent = p.descent();
//				float fSpacing = p.getFontSpacing();
//				
//				Rect bds = new Rect();
//				p.getTextBounds(str,0,str.length(),bds);
//				Log.d("font", 
//						String.valueOf(p.getTextSize()) + ", " +
//						String.valueOf(flMeas) + ", " +
//						String.valueOf(fAscent) + ", " +
//						String.valueOf(fDescent) + ", " +
//						String.valueOf(fSpacing) + ", " +
//						bds.toString() + "," +
//						String.valueOf(bds.width()) + ", " +
//						String.valueOf(bds.height()) 
//						);
				
			}
			rcFontBounds[2] = Utility.Justify(rcFontBounds[2], rcUpperValue, Utility.BOXJUSTIFY.CENTER_CENTER);
			
			// This one is for the labels
			rcFontBounds[3] = Utility.GetNeededFontSize("km/h", p, rcUpperLabel); // km/h
			fontSize[3] = p.getTextSize();

			// This one is for the time, minutes, sec, tenths
			rcFontBounds[4] = Utility.GetNeededFontSize("4:44.4", p, rcLowerValue);
			fontSize[4] = p.getTextSize();
			
			rcFontBounds[5] = Utility.GetNeededFontSize("4:44.4", p, rcUpperValue);
			fontSize[5] = p.getTextSize();

			rcFontBounds[6] = Utility.GetNeededFontSize("km/h", p, rcLowerLabel); // Best			
			fontSize[6] = p.getTextSize();

			if( rcAll.width() > rcAll.height() ) {
				// landscape adjustments
				rcFontBounds[2] = Utility.Justify(rcFontBounds[2], rcUpperValue, Utility.BOXJUSTIFY.CENTER_TOP);
				rcFontBounds[3] = Utility.Justify(rcFontBounds[3], rcUpperLabel, Utility.BOXJUSTIFY.CENTER_BOTTOM);
				rcFontBounds[5] = Utility.Justify(rcFontBounds[5], rcUpperValue, Utility.BOXJUSTIFY.CENTER_TOP);
				rcFontBounds[6] = Utility.Justify(rcFontBounds[6], rcLowerLabel, Utility.BOXJUSTIFY.CENTER_BOTTOM);
				rcFontBounds[4] = Utility.Justify(rcFontBounds[4], rcLowerValue, Utility.BOXJUSTIFY.CENTER_TOP);
			}
			else {
				rcFontBounds[2] = Utility.Justify(rcFontBounds[2], rcUpperValue, Utility.BOXJUSTIFY.CENTER_CENTER);
				rcFontBounds[3] = Utility.Justify(rcFontBounds[3], rcUpperLabel, Utility.BOXJUSTIFY.CENTER_CENTER);
				rcFontBounds[4] = Utility.Justify(rcFontBounds[4], rcLowerValue, Utility.BOXJUSTIFY.CENTER_CENTER);
				rcFontBounds[5] = Utility.Justify(rcFontBounds[5], rcUpperValue, Utility.BOXJUSTIFY.CENTER_CENTER);
				rcFontBounds[6] = Utility.Justify(rcFontBounds[6], rcLowerLabel, Utility.BOXJUSTIFY.CENTER_CENTER);			}
			fontInitialized = true; 
		}

		lapLast = myApp.GetLastLap();

		final double dLastLap;
		final String strLast;
		final double dBestLap;
		final double flThisTime;
		if( lap == null) // this will trigger as the app is exited, because laps are nulled
			return;
		else flThisTime = ((double)lap.GetAgeInMilliseconds())/1000.0;

		String strBest = "";

		if(lapLast != null && lapBest != null)
		{
			DrawPlusMinusNew(canvas, rcTimeDiff, lap, lapBest,pDelta);
			dBestLap = lapBest.GetLapTime();
			strBest = buildLapTime(dBestLap);

			if( flThisTime < 3 ) {
				// Display last lap time for the first 10 seconds of the next lap
				dLastLap = lapLast.GetLapTime();
				if( dLastLap > dBestLap )
					p.setColor(Color.RED); // last lap worse, make red
				else
					p.setColor(Color.GREEN); // last lap better/equal, make green
				strLast = buildLapTime(dLastLap);
				Utility.DrawFontInBoxFinal(canvas, strLast, fontSize[5], p, rcFontBounds[5], Utility.TEXTJUSTIFY.CENTER);
				Utility.DrawFontInBoxFinal(canvas, "Last", fontSize[3], p, rcFontBounds[3], Utility.TEXTJUSTIFY.CENTER);
			}
			else {
				final TimePoint2D ptCurrent = lap.GetLastPoint();
				final float flSpeed = (float)ptCurrent.dVelocity;
				num.setMaximumFractionDigits(0);
				String strSpeed = Prefs.FormatMetersPerSecond(flSpeed,num,eDisplayUnitSystem,false);
				Utility.DrawFontInBoxFinal(canvas, strSpeed, fontSize[2], p, rcFontBounds[2], Utility.TEXTJUSTIFY.CENTER);
				Utility.DrawFontInBoxFinal(canvas, Prefs.GetSpeedUnits(eDisplayUnitSystem), fontSize[3], p, rcFontBounds[3],Utility.TEXTJUSTIFY.CENTER);
			}
		}
		else // First lap, or best lap has been reset
		{
			p.setColor(Color.WHITE); // reset to white

			final String strLapTime = buildLapTime(flThisTime);
			Utility.DrawFontInBoxFinal(canvas, strLapTime, fontSize[5], p, rcFontBounds[5], Utility.TEXTJUSTIFY.CENTER);
			Utility.DrawFontInBoxFinal(canvas, "Lap", fontSize[3], p, rcFontBounds[3], Utility.TEXTJUSTIFY.CENTER);
		}
		
		p.setColor(Color.WHITE); // Best lap in white
		Utility.DrawFontInBoxFinal(canvas, strBest, fontSize[4], p, rcFontBounds[4], Utility.TEXTJUSTIFY.CENTER);
		Utility.DrawFontInBoxFinal(canvas, "Best", fontSize[6], p, rcFontBounds[6],Utility.TEXTJUSTIFY.CENTER);
	
	}
	
	public String buildLapTime( double flLapTime)
	{
		final int minutes = (int)(flLapTime/60);
		final int seconds = (int)(flLapTime - minutes*60);
		final int tenths = (int)((10* (flLapTime - minutes*60 - seconds)));
		String strLapTime = minutes + ":";
		if (seconds <10 )
			strLapTime = strLapTime + "0" + seconds + "." + tenths;
		else
			strLapTime = strLapTime + seconds + "." + tenths;
		return strLapTime;
	}
	public void onDraw(Canvas canvas)
	{
		myMatrix.reset();
		canvas.setMatrix(myMatrix);
		//canvas.scale(1.5f,1.5f);
		canvas.clipRect(getLeft(),getTop(),getRight(),getBottom(),Op.REPLACE);
		
		String strMsg = myApp.GetMessage();
		
		rcAll.set(getLeft(),getTop(),getRight(), getBottom());
		
		if((myApp.GetTimeSinceLastSplit() < 3.0) && !(strSpeedoStyle.equals(LandingOptions.SPEEDO_LAPTIMER)))
		{
			// draw the last split
			num.setMaximumFractionDigits(2);
			num.setMinimumFractionDigits(2);
			
			paintBigText.setARGB(255,255,255,255); // just use white if they're going for absolute times
			
			final int cxLabels = getWidth()/10;
			final int cxSplit = getRight() - cxLabels;
			final int hSplit = getTop() + getHeight()/2;
			
			rcLapTime.set(getLeft(),getTop(),cxSplit,hSplit);
			rcLapLabel.set(cxSplit, getTop(),getRight(),hSplit);
			rcBestTime.set(getLeft(),hSplit,cxSplit,getBottom());
			rcBestLabel.set(cxSplit, hSplit,getRight(),getBottom());
			// curent lap has no splits, so we must have just finished a lap
			lapLast = myApp.GetLastLap();
			lapBest = myApp.GetBestLap(); 
			if(lapLast != null)
			{
				final double dLastLap = lapLast.GetLapTime();
				String strLapText = Utility.FormatSeconds((float)dLastLap);
				Utility.DrawFontInBox(canvas, strLapText, paintBigText, rcLapTime);
				Utility.DrawFontInBox(canvas, "Lap", paintBigText, rcLapLabel);
			}
			if(lapBest != null)
			{
				final double dBestLap = lapBest.GetLapTime();
				String strBestText = Utility.FormatSeconds((float)dBestLap);
				Utility.DrawFontInBox(canvas, strBestText, paintBigText, rcBestTime);
				Utility.DrawFontInBox(canvas, "Best", paintBigText, rcBestLabel);
			}
		}
		else if(strMsg != null)
		{
			Utility.DrawFontInBox(canvas, strMsg, paintBigText, rcAll);
		}
		else
		{
			final float cxSecondaryPct = 0.25f;
			final int cxSecondaryPixels = (int)(getWidth() * cxSecondaryPct);
			
			rcMain.set(getLeft(), getTop(), getRight()-cxSecondaryPixels, getBottom());
			
			rcSecondary.set(rcMain.right,getTop(),getRight(),getBottom());

			lap = myApp.GetCurrentLap();
			lapBest = myApp.GetBestLap();
			if(strSpeedoStyle.equals(LandingOptions.SPEEDO_SPEEDDISTANCE))
			{
				DrawSpeedDistance(canvas, rcMain, lap, lapBest);
				if(lapBest != null)
				{
					DrawPlusMinus(canvas, rcSecondary, lap, lapBest);
				}
			}
			else if(strSpeedoStyle.equals(LandingOptions.SPEEDO_COMPARATIVE))
			{
				DrawComparative(canvas, rcMain, lap, lapBest);
			}
			else if(strSpeedoStyle.equals(LandingOptions.SPEEDO_LIVEPLUSMINUS))
			{
				DrawPlusMinus(canvas, rcMain, lap, lapBest);
			}
			else if(strSpeedoStyle.equals(LandingOptions.SPEEDO_SIMPLE))
			{
				DrawComparative(canvas, rcMain, lap, null);
			}
			else if(strSpeedoStyle.equals(LandingOptions.SPEEDO_LAPTIMER))
			{
				DrawLapTimer(canvas, rcAll, lap, lapBest);
			}
		}
	}
}

