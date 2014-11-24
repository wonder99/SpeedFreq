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


import android.annotation.TargetApi;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.Spinner;

public class ConfigureGPSActivity extends Activity implements OnCheckedChangeListener
{
	private BroadcastListener m_listener;
	Spinner spn;
	String strDefault;
	
	@Override
	public void onCreate(Bundle extras)
	{
		super.onCreate(extras);
		setContentView(R.layout.configuregps);
	}
	
	@Override
	public void onResume()
	{
		super.onResume();

		m_listener = new BroadcastListener();
		IntentFilter btFilter = new IntentFilter();
		btFilter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
		this.registerReceiver(m_listener, btFilter);

		SharedPreferences settings = this.getSharedPreferences(Prefs.SHAREDPREF_NAME, 0);
		
		spn = (Spinner)findViewById(R.id.spnGPS);
		CheckBox chk = (CheckBox)findViewById(R.id.chkGPS);
		CheckBox chkBtInsecure = (CheckBox)findViewById(R.id.chkBtInsecure);
		
		boolean fGPS = settings.getBoolean(Prefs.PREF_BTGPSENABLED_BOOL, false);
		chk.setChecked(fGPS);
		chk.setOnCheckedChangeListener(this);
		
		strDefault = settings.getString(Prefs.PREF_BTGPSNAME_STRING, "");
		LandingRaceBase.SetupBTSpinner(this, spn, strDefault);
//		spn.setEnabled(fGPS);

		boolean bBtInsecure = settings.getBoolean(Prefs.PREF_BTINSECURE_BOOL, false);
		chkBtInsecure.setChecked(bBtInsecure);
		
		// Disable the checkbox if android version is too old to use it
		if( Build.VERSION.SDK_INT < Build.VERSION_CODES.GINGERBREAD_MR1 )
			chkBtInsecure.setEnabled(false);

	}
	
	@TargetApi(Build.VERSION_CODES.GINGERBREAD)
	@Override
	public void onPause()
	{
		super.onPause();

		this.unregisterReceiver(m_listener);


		SharedPreferences settings = this.getSharedPreferences(Prefs.SHAREDPREF_NAME, 0);
		Spinner spn = (Spinner)findViewById(R.id.spnGPS);
		CheckBox chk = (CheckBox)findViewById(R.id.chkGPS);
		CheckBox chkBtInsecure = (CheckBox)findViewById(R.id.chkBtInsecure);
		boolean bBtInsecure = chkBtInsecure.isChecked();
		boolean bBTGPSEn = chk.isChecked();
		String strValue = "";
		Object selected = spn.getSelectedItem();
		if(selected != null)
		{
			strValue = selected.toString();
		}

		SharedPreferences.Editor edit = settings.edit();

		edit.putString(Prefs.PREF_BTGPSNAME_STRING, strValue);
		edit.putBoolean(Prefs.PREF_BTINSECURE_BOOL, bBtInsecure);
		edit.putBoolean(Prefs.PREF_BTGPSENABLED_BOOL, bBTGPSEn);
		  
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD)
			edit.apply();
		else
			edit.commit();
	}

	@Override
	public void onCheckedChanged(CompoundButton arg0, boolean arg1) 
	{
	}
	
    private class BroadcastListener extends BroadcastReceiver
    {
    	@Override
		public void onReceive(Context ctx, Intent intent)
		{
    		if(intent.getAction().equals(BluetoothAdapter.ACTION_STATE_CHANGED) )
    		{
    			BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
				strDefault = spn.getSelectedItem().toString();
				if( true ||ba.isEnabled()) {
					LandingRaceBase.SetupBTSpinner(ctx, spn, strDefault);
					
				}
    		}
		}
    }

}
