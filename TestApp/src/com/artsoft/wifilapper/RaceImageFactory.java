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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import android.graphics.Bitmap;
import android.os.Handler;

public class RaceImageFactory 
{
	private Map<Long,Bitmap> m_map;
	private Handler m_handler; // used to report to our owner when things happen (new images, etc)
	private RaceLoader m_thd;
	private int m_ixMessage; // what message we should send to the handler when new images are available
	private boolean bIsATrack;
	public RaceImageFactory(Handler handler, int ixMessage, boolean bIsATrack)
	{
		m_thd = new RaceLoader();
		m_thd.start();
		m_map = new HashMap<Long,Bitmap>();
		m_handler = handler;
		m_ixMessage = ixMessage;
		this.bIsATrack = bIsATrack;
	}
	public synchronized void Shutdown()
	{
		if(m_thd != null)
		{
			m_thd.Shutdown();
			m_thd.interrupt();
			m_thd = null;
		}
		m_map = null;
		m_handler = null;
		
	}
	private synchronized void SetImage(long lRaceId, Bitmap bmp)
	{
		if(m_map != null)
		{
			m_map.put(Long.valueOf(lRaceId), bmp);
			m_handler.sendEmptyMessage(m_ixMessage);
		}
	}
	public Bitmap GetImage(long lRaceId, int width, int height, boolean fRequestOnWorker)
	{
		synchronized(this)
		{
			if(m_map != null && m_map.containsKey(Long.valueOf(lRaceId)))
			{
				return m_map.get(Long.valueOf(lRaceId));
			}
		}
		// if we got here, we don't currently have this race's image cached.  Return null for now and fire an update request
		if(!fRequestOnWorker)
		{
			ThdLoadImage(lRaceId, width, height);
		}
		return null;
	}
	
	private void ThdLoadImage(long lRaceId, int width, int height)
	{
		RaceLoader thd = null;
		synchronized(this)
		{
			thd = m_thd;
		}
		
		if(thd != null)
		{
			thd.AddTarget(lRaceId, width, height);
		}
	}
	
	private class RaceLoader extends Thread implements Runnable
	{
		private class Request {
			long lRequest;
			int width;
			int height;
			public Request(long req, int w, int h) {
				lRequest = req;
				width = w;
				height = h;
			}
		}
		private List<Request> lstRequests;
		private boolean m_fContinue;
		public RaceLoader()
		{
			lstRequests = new ArrayList<Request>();
			m_fContinue = true;
		}
		public synchronized void AddTarget(long lRaceId, int width, int height)
		{
			lstRequests.add(new Request(lRaceId,width,height));
			notify();
		}
		public synchronized void Shutdown()
		{
			m_fContinue = false;
		}
		public void run()
		{
			Thread.currentThread().setName("Race image thread");
			while(m_fContinue)
			{
				// check for requests
				try 
				{
					Request rThisRqst=null;
					synchronized(this)
					{
						wait();
					}
					
					while(m_fContinue)
					{
						// we must have a request.
						synchronized(this)
						{
							if(lstRequests.size() > 0)
							{
								rThisRqst = lstRequests.get(0);
								lstRequests.remove(0);
							}
							else
							{
								break;
							}
							if(RaceImageFactory.this.GetImage(rThisRqst.lRequest, rThisRqst.width, rThisRqst.height, true) != null)
							{
								continue; // we already have this image, no need to re-render it.  go to the next
							}
						}
						if(rThisRqst != null)
						{
							if( bIsATrack )
							{
								Bitmap bmp = RaceDatabase.GetBitmapFromDatabase(RaceDatabase.Get(), rThisRqst.lRequest, rThisRqst.width, rThisRqst.height);
								RaceImageFactory.this.SetImage(rThisRqst.lRequest, bmp);
							}
							else
							{
								Bitmap bmp = RaceDatabase.GetRaceOutlineImage(RaceDatabase.Get(), rThisRqst.lRequest, rThisRqst.width, rThisRqst.height);
								RaceImageFactory.this.SetImage(rThisRqst.lRequest, bmp);
							}
								
						}
					}
				} 
				catch (InterruptedException e) 
				{
					// we're done
					break;
				}
			}
		}
	}
}
