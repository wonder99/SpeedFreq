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
import java.util.List;

import com.artsoft.wifilapper.OBDThread.PIDParameter;
import com.artsoft.wifilapper.OBDThread.PIDSupportListener;

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
import android.os.Handler;
import android.os.Message;
import android.view.LayoutInflater;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.CompoundButton.OnCheckedChangeListener;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.Toast;

public class ConfigureOBD2Activity extends Activity implements OnCheckedChangeListener,PIDSupportListener,Handler.Callback, OnClickListener
{
	private List<Integer> lstSelectedPIDs;
	private Handler m_handler;
	private String m_strOBD2Error;
	private BroadcastListener m_listener;
	Button btnScan;
	Spinner spn;
	String strDefault;
	
	private static final int MSG_OBD2 = 50;
	
	@Override
	public void onCreate(Bundle extras)
	{
		super.onCreate(extras);
		m_handler = new Handler(this);
		setContentView(R.layout.configureobd2);
		btnScan = (Button)findViewById(R.id.btnScanPIDs);
		spn = (Spinner)findViewById(R.id.spnOBD2);

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
		
		CheckBox chk = (CheckBox)findViewById(R.id.chkOBD2);
		boolean fOBD2 = settings.getBoolean(Prefs.PREF_BTOBD2ENABLED_BOOL, false);
		chk.setChecked(fOBD2);
		chk.setOnCheckedChangeListener(this);
		
		strDefault = settings.getString(Prefs.PREF_BTOBD2NAME_STRING, "");
		
		LandingRaceBase.SetupBTSpinner(this, spn, strDefault);

		btnScan.setEnabled(spn.isEnabled());
		btnScan.setOnClickListener(this);

		ListView lstOBD2PIDs = (ListView)findViewById(R.id.lstPIDs);

		List<PIDParameter> items = new ArrayList<PIDParameter>();
		lstSelectedPIDs = new ArrayList<Integer>();
		Prefs.LoadOBD2PIDs(settings, lstSelectedPIDs);
		
		for(int x = 0;x < lstSelectedPIDs.size(); x++)
		{
			items.add(new PIDParameter(lstSelectedPIDs.get(x)));
		}

		FillOBD2List(lstOBD2PIDs,items);
	}
	
	@TargetApi(Build.VERSION_CODES.GINGERBREAD)
	private SharedPreferences.Editor SaveOBD2PIDs(SharedPreferences.Editor edit, ListView lstOBD2)
	{
		// first, wipe out all the old PIDs
		for(int x = 0;x < 256; x++)
		{
			edit = edit.putBoolean("pid" + x, false);
		}
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD)
			edit.apply();
		else
			edit.commit();
		for(int x = 0;x < lstOBD2.getCount(); x++)
		{
			PIDParameterItem pid = (PIDParameterItem)lstOBD2.getItemAtPosition(x);
			if(pid.IsChecked())
			{
				edit = edit.putBoolean("pid" + pid.ixCode, true);
			}
		}
		return edit;
	}
	
	@TargetApi(Build.VERSION_CODES.GINGERBREAD)
	@Override
	public void onPause()
	{
		super.onPause();
		this.unregisterReceiver(m_listener);

		SharedPreferences settings = this.getSharedPreferences(Prefs.SHAREDPREF_NAME, 0);

		ListView lstOBD2PIDs = (ListView)findViewById(R.id.lstPIDs);
		
		String strValue = "";
		Object obj = spn.getSelectedItem();
		if(obj != null)
		{
			strValue = obj.toString();
		}

		CheckBox chk = (CheckBox)findViewById(R.id.chkOBD2);
		
		SharedPreferences.Editor edit = settings.edit();
		edit = edit.putString(Prefs.PREF_BTOBD2NAME_STRING, strValue);
		edit = edit.putBoolean(Prefs.PREF_BTOBD2ENABLED_BOOL, chk.isChecked());
		
		edit = SaveOBD2PIDs(edit, lstOBD2PIDs);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.GINGERBREAD)
			edit.apply();
		else
			edit.commit();
	}

	@Override
	public void onCheckedChanged(CompoundButton arg0, boolean arg1) 
	{
		if(arg0.getId() == R.id.chkOBD2)
		{
			// don't need to do anything
		}
	}
	
	private static class PIDParameterItem extends PIDParameter
	{
		public PIDParameterItem(int ixCode, String strDesc, boolean fChecked) 
		{
			super(ixCode, strDesc);
			this.fChecked = fChecked;
		}
		public void SetChecked(boolean f)
		{
			fChecked = f;
		}
		public boolean IsChecked()
		{
			return fChecked;
		}
		private boolean fChecked = false;
	}
	private static class PIDParameterAdapter extends ArrayAdapter<PIDParameterItem> implements OnCheckedChangeListener
	{
	    private List<PIDParameterItem> items;

	    public PIDParameterAdapter(Context context, int textViewResourceId, List<PIDParameterItem> objects) 
	    {
	        super(context, textViewResourceId, objects);

	        this.items = objects;
	    }

	    @Override
	    public View getView(int position, View convertView, ViewGroup parent) 
	    {
	        View v = convertView;
	        if (v == null) 
	        {
	            LayoutInflater vi = (LayoutInflater) getContext().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
	            v = vi.inflate(R.layout.obd2list_item, null);
	        }
	        PIDParameterItem myobject = items.get(position);

	        if (myobject!=null)
	        {
	            //checkbox
	            CheckBox cbEnabled = (CheckBox) v.findViewById(R.id.chkSelect);
	            if(cbEnabled != null)
	            {
	                cbEnabled.setText(myobject.toString());
	                cbEnabled.setTag(Integer.valueOf(position));
	                cbEnabled.setOnCheckedChangeListener(null);
	                cbEnabled.setChecked(myobject.fChecked);
	                cbEnabled.setOnCheckedChangeListener(this);
	            }
	        }

	        return v;
	    }

		@Override
		public void onCheckedChanged(CompoundButton buttonView,boolean isChecked) 
		{
			int iPos = ((Integer)buttonView.getTag()).intValue();
			items.get(iPos).SetChecked(isChecked);
		}
	}
	
	private void FillOBD2List(ListView lstUI, List<PIDParameter> lstData)
	{
		List<PIDParameterItem> lstItems = new ArrayList<PIDParameterItem>();
		if(lstData != null) // call with null to empty the list?
		{
			for(int x = 0; x < lstData.size(); x++)
			{
				PIDParameter pid = lstData.get(x);
				lstItems.add(new PIDParameterItem(pid.ixCode, pid.strDesc, this.lstSelectedPIDs.contains(Integer.valueOf(pid.ixCode))));
			}
		}
		
		PIDParameterAdapter adapter = new PIDParameterAdapter(this, R.layout.obd2list_item, lstItems);

		lstUI.setAdapter(adapter);
		Utility.SetListViewHeightBasedOnChildren(lstUI);
	}
	@Override
	public void NotifyPIDSupport(List<PIDParameter> lstPIDs)
	{
		Message m = Message.obtain(m_handler, MSG_OBD2, lstPIDs);
		m_handler.sendMessage(m);
	}

	@Override
	public boolean handleMessage(Message msg) 
	{
		switch(msg.what)
		{
		case MSG_OBD2:
			if(m_strOBD2Error != null)
			{
				Toast.makeText(this, "OBD2 error: " + m_strOBD2Error, Toast.LENGTH_LONG).show();
				m_strOBD2Error = null;
			}
			@SuppressWarnings("unchecked")
			List<PIDParameter> lstPIDs = (List<PIDParameter>)msg.obj; 
			if(lstPIDs != null)
			{
				ListView lstOBD2 = (ListView)findViewById(R.id.lstPIDs);
				FillOBD2List(lstOBD2, lstPIDs);
				Toast.makeText(this, "Found " + lstPIDs.size() + " supported OBD2 parameters.", Toast.LENGTH_LONG).show();
			}
			else
			{
				Toast.makeText(this, "No available OBD2 parameters.  Did you select the correct device and is it in range?", Toast.LENGTH_LONG).show();
			}
			Button btnSearch = (Button)findViewById(R.id.btnScanPIDs);
			btnSearch.setEnabled(true);
			return true;
		}
		return false;
	}

	@Override
	public void onClick(View arg0) 
	{
		if(arg0.getId() == R.id.btnScanPIDs)
		{
			if(spn.getChildCount() > 0 && spn.getSelectedItem() != null)
			{
				final String strBT = spn.getSelectedItem().toString();
				OBDThread.ThdGetSupportedPIDs(strBT,this);

				Button btnSearch = (Button)findViewById(R.id.btnScanPIDs);
				btnSearch.setEnabled(false);
				Toast.makeText(this, "Querying device '" + strBT + "'", Toast.LENGTH_LONG).show();
			}
			else
			{
				Toast.makeText(this, "You must select a bluetooth device first", Toast.LENGTH_LONG).show();
			}
		}
	}

	@Override
	public void NotifyOBD2Error(String str) 
	{
		m_strOBD2Error = str;
		this.m_handler.sendEmptyMessage(MSG_OBD2);
	}
	
    private class BroadcastListener extends BroadcastReceiver
    {
    	@Override
		public void onReceive(Context ctx, Intent intent)
		{
    		if(intent.getAction().equals(BluetoothAdapter.ACTION_STATE_CHANGED) )
    		{
    			BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
				btnScan.setEnabled(ba.isEnabled());
				strDefault = spn.getSelectedItem().toString();
				if( true ||ba.isEnabled()) {
					LandingRaceBase.SetupBTSpinner(ctx, spn, strDefault);
					
				}
    		}
		}
    }

}
