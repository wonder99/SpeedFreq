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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import android.app.Activity;
import android.app.Dialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemSelectedListener;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

public abstract class LandingRaceBase extends Activity implements OnItemSelectedListener
{
	private BroadcastListener m_listener;
	private boolean m_fRequireWifi;
	private String lastSSID;
	
	@Override
	public void onCreate(Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);
		getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
		lastSSID = null;
	}
	
	@Override
	public void onResume()
	{
		super.onResume();
		
		SharedPreferences settings = getSharedPreferences(Prefs.SHAREDPREF_NAME, 0);
		m_fRequireWifi = settings.getBoolean(Prefs.PREF_REQUIRE_WIFI, Prefs.DEFAULT_REQUIRE_WIFI);
		
		if(CareAboutWifi())
		{
			m_listener = new BroadcastListener();
			IntentFilter wifiFilter = new IntentFilter();
//			wifiFilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
			wifiFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
			this.registerReceiver(m_listener, wifiFilter);
		}
	}
	@Override
	public void onPause()
	{
		super.onResume();
		
		if(CareAboutWifi())
		{
			this.unregisterReceiver(m_listener);
		}
	}
	
	private static class BTListItem extends Object
	{
		private BluetoothDevice bd;
		public BTListItem(BluetoothDevice bd)
		{
			this.bd = bd;
		}
		public String toString()
		{
			if(bd == null)
			{
				return "None";
			}
			else
			{
				return bd.getName();
			}
		}
	}

	protected static class RenameDialog<T> extends Dialog implements View.OnClickListener
	{
		private T obj;
		private int param; // extra info the caller can pass in.  Example: LoadRace lets itself know if the dialog was shown because of a context-click or because of a race being copied
		private boolean fCancelled;
		public RenameDialog(Context ctx, String strTitle, T obj, int param)
		{
			super(ctx);
			Init(strTitle, obj, null, param);
		}
		public RenameDialog(Context ctx, String strTitle, T obj, String strOverride, int param)
		{
			super(ctx);
			Init(strTitle, obj, strOverride, param);
		}
		private void Init(String strTitle, T obj, String strOverride, int param)
		{
			this.param = param;
			this.obj = obj;
			setContentView(R.layout.renamedialog);
			setTitle(strTitle);
			
			EditText edt = (EditText)findViewById(R.id.edtRename);
			edt.setText(strOverride == null ? obj.toString() : strOverride);
			
			Button btnOk = (Button)findViewById(R.id.btnApply);
			btnOk.setOnClickListener((View.OnClickListener)this);
			
			fCancelled = true; // assume we're cancelled unless they click ok
		}
		int GetParam()
		{
			return param;
		}
		T GetData()
		{
			return obj;
		}
		String GetResultText()
		{
			EditText edt = (EditText)findViewById(R.id.edtRename);
			return edt.getText().toString();
		}
		boolean WasCancelled() {return fCancelled;}
		@Override
		public void onClick(View v) 
		{
			if(v.getId() == R.id.btnApply)
			{
				fCancelled = false;
				dismiss();
			}
		}
	}

    protected void SetupSSIDSpinner(Spinner spn, String strDefault)
    {
    	if(!CareAboutWifi()) return;
    	
    	try
    	{
    		WifiManager pWifi = (WifiManager)getSystemService(Context.WIFI_SERVICE);
    		List<String> lstSSIDs = new ArrayList<String>();
			int ixDefault = -1;
    		
			List<WifiConfiguration> lstNetworks = pWifi.getConfiguredNetworks();

			if( lstNetworks != null )  // will be null if wifi is off
			{    		
				// Sort the list alphabetically, ignoring case
				Collections.sort(lstNetworks, new Comparator<WifiConfiguration>(){
					public int compare(WifiConfiguration emp1, WifiConfiguration emp2) {
						return emp1.SSID.compareToIgnoreCase(emp2.SSID);
					} }
						);

				for(int x = 0;x < lstNetworks.size(); x++)
				{
					String strSSID = lstNetworks.get(x).SSID;
					strSSID = strSSID.replace("\"", "");
					lstSSIDs.add(strSSID);
					if(strSSID.equalsIgnoreCase(strDefault))
					{
						ixDefault = x;
					}
				}
			}
			else	// wifi is off, so just put the default SSID in the box, which will be disabled 
			{
				lstSSIDs.add(strDefault);
				ixDefault = 0;
			}
			ArrayAdapter<String> adapter = new ArrayAdapter<String> (this, R.layout.list_item, lstSSIDs);
			adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
			spn.setAdapter(adapter);
			if(ixDefault >= 0) 
			{
				spn.setSelection(ixDefault,true);
			}
			spn.setOnItemSelectedListener(this);
	        	
    	}
        catch(Exception e)
        {
//        	spn.setEnabled(false);
        }
    }
    
    private boolean CareAboutWifi()
    {
    	return m_fRequireWifi;
    }
    
    @Override
    public void onItemSelected(AdapterView<?> arg0, View arg1,int arg2, long arg3) 
    {
    	if(arg0.getId() == R.id.spnSSID && CareAboutWifi())
    	{
    		WifiManager pWifi = (WifiManager)getSystemService(Context.WIFI_SERVICE);
    		
    		Spinner spn = (Spinner)arg0;
    		
    		String strSSID = spn.getSelectedItem().toString();
    		if(strSSID != null && strSSID.length() > 0)
    		{
    			// reset the last SSID, so we get a notification when new one is connected
    			lastSSID = "no way this could be your SSID";
    			// they've selected a valid SSID.  Let's try to connect to it
    			if(Utility.ConnectToSSID(strSSID, pWifi))
    			{
//    				Toast.makeText(this, "Attempting to connect to '" + strSSID + "'", Toast.LENGTH_SHORT).show();
    			}
    		}
    	}
    	arg0.invalidate();
    }

    @Override
    public void onNothingSelected(AdapterView<?> arg0) {
        // TODO Auto-generated method stub

    }
    public static boolean SetupBTSpinner(Context ctx, Spinner spn, String strDefault)
    {
    	try
    	{
	    	BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
	    	if(ba != null && ba.isEnabled())
	    	{
		    	Set<BluetoothDevice> setDevices = ba.getBondedDevices(); 
		    	Iterator<BluetoothDevice> i = setDevices.iterator();
		    	
		    	List<BTListItem> lstDevices = new ArrayList<BTListItem>();
		    	
		    	while(i.hasNext())
		    	{
		    		BluetoothDevice bd = i.next();
		    		lstDevices.add(new BTListItem(bd));
		    	}
		    	
		    	int ixDefault = -1;
		    	for(int x = 0;x < lstDevices.size(); x++)
		    	{
		    		if(lstDevices.get(x).toString().equalsIgnoreCase(strDefault))
		    		{
		    			ixDefault = x;
		    		}
		    	}
		    	
		        ArrayAdapter<BTListItem> adapter = new ArrayAdapter<BTListItem>(ctx, R.layout.list_item, lstDevices);
		        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		        spn.setAdapter(adapter);
		        if(ixDefault >= 0) spn.setSelection(ixDefault);
		        spn.setEnabled(true);
		        spn.invalidate();
		        return true;
	    	}
	    	else
	    	{
		        ArrayAdapter<String> adapter = new ArrayAdapter<String>(ctx, R.layout.list_item);
		        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		        adapter.add(strDefault);
		        spn.setAdapter(adapter);
		        spn.setSelection(0);
	    		spn.setEnabled(false);
		        spn.invalidate();
	    		return false;
	    	}
    	}
        catch(Exception e)
        {
        	spn.setEnabled(false);
        	return false;
        }
    }
    
    protected abstract void SetIPString(String strIP);
    
    // null -> not connected
    // non-null -> connected to that network
    protected void NotifyWifiChange(String strSSID)
    {
    	if(strSSID != null)
    	{
    		Toast.makeText(this,"Connected to '" + strSSID + "'",Toast.LENGTH_SHORT).show();
    	}
    }
    
    private static final int AUTO_IP = 1;
    private static final int SEL_TRACK = 2;
    protected void ShowAutoIPActivity()
    {
    	startActivityForResult(new Intent(this,ComputerChooserActivity.class),AUTO_IP);
    }
    protected void ShowTrackActivity()
    {
    	startActivityForResult(new Intent(this,LandingTracks.class),SEL_TRACK);
    }
    protected void onActivityResult(int requestCode, int resultCode,Intent data) 
    {
        if (requestCode == AUTO_IP) 
        {
            if (resultCode == RESULT_OK) 
            {
            	String strIP = data.getStringExtra(Prefs.IT_IP_STRING);
            	SetIPString(strIP);
            }
        }
        if (requestCode == SEL_TRACK) 
        {
            if (resultCode == RESULT_OK) 
            {
            	String strIP = data.getStringExtra(Prefs.IT_TRACK_ID);
            	SetIPString(strIP);
            }
        }
    }

    private class BroadcastListener extends BroadcastReceiver
    {
    	@Override
		public void onReceive(Context ctx, Intent intent)
		{
    		if(intent.getAction().equals(WifiManager.NETWORK_STATE_CHANGED_ACTION))
    		{
    			String strSSID = intent.getStringExtra(WifiManager.EXTRA_BSSID);
    			if(strSSID != null)
    			{
    				WifiManager pWifi = (WifiManager)getSystemService(Context.WIFI_SERVICE);
    				WifiInfo pInfo = pWifi.getConnectionInfo();
    				if(pInfo.getIpAddress() != 0) // only tell them when we're fully connected
    				{
    					strSSID = pInfo.getSSID();
    				}
    				else
    				{
    					strSSID = null;
    				}
    			}
    			// prevent message if the connected SSID is the same as the last, or if
    			// this is the first invocation
    			if( lastSSID != null && strSSID != null && !strSSID.equalsIgnoreCase(lastSSID) ) {
    				NotifyWifiChange(strSSID);
    			}
				lastSSID = strSSID;
    		}
		}
    }
}
