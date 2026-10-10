/*
 * Copyright (c) 2013 L2jMobius
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be
 * included in all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR
 * IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package org.l2jmobius.gameserver.entity.zone.form;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.itemcontainer.Inventory;
import org.l2jmobius.gameserver.entity.zone.ZoneForm;
import org.l2jmobius.gameserver.geoengine.GeoEngine;

/**
 * A primitive circular zone
 * @author durgus, Mobius
 */
public class ZoneCylinder extends ZoneForm
{
	private final int _x;
	private final int _y;
	private final int _z1;
	private final int _z2;
	private final int _rad;
	private final int _radS;
	private final Location _centerPoint;
	
	public ZoneCylinder(int x, int y, int z1, int z2, int rad)
	{
		_x = x;
		_y = y;
		_z1 = z1;
		_z2 = z2;
		_rad = rad;
		_radS = rad * rad;
		_centerPoint = new Location(_x, _y, (_z1 + _z2) / 2);
	}
	
	@Override
	public boolean isInsideZone(int x, int y, int z)
	{
		final double dx = _x - x;
		final double dy = _y - y;
		return (((dx * dx) + (dy * dy)) <= _radS) && (z >= _z1) && (z <= _z2);
	}
	
	@Override
	public boolean intersectsRectangle(int ax1, int ax2, int ay1, int ay2)
	{
		// Circles point inside the rectangle?
		if ((_x > ax1) && (_x < ax2) && (_y > ay1) && (_y < ay2))
		{
			return true;
		}
		
		// Any point of the rectangle intersecting the Circle?
		final double dx1 = ax1 - _x;
		final double dy1 = ay1 - _y;
		final double dx1Sq = dx1 * dx1;
		final double dy1Sq = dy1 * dy1;
		if ((dx1Sq + dy1Sq) < _radS)
		{
			return true;
		}
		
		final double dy2 = ay2 - _y;
		final double dy2Sq = dy2 * dy2;
		if ((dx1Sq + dy2Sq) < _radS)
		{
			return true;
		}
		
		final double dx2 = ax2 - _x;
		final double dx2Sq = dx2 * dx2;
		if ((dx2Sq + dy1Sq) < _radS)
		{
			return true;
		}
		
		if ((dx2Sq + dy2Sq) < _radS)
		{
			return true;
		}
		
		// Collision on any side of the rectangle?
		if ((_x > ax1) && (_x < ax2))
		{
			if (Math.abs(_y - ay2) < _rad)
			{
				return true;
			}
			
			if (Math.abs(_y - ay1) < _rad)
			{
				return true;
			}
		}
		
		if ((_y > ay1) && (_y < ay2))
		{
			if (Math.abs(_x - ax2) < _rad)
			{
				return true;
			}
			
			if (Math.abs(_x - ax1) < _rad)
			{
				return true;
			}
		}
		
		return false;
	}
	
	@Override
	public double getDistanceToZone(int x, int y)
	{
		final double dx = _x - x;
		final double dy = _y - y;
		return Math.sqrt((dx * dx) + (dy * dy)) - _rad;
	}
	
	// getLowZ() / getHighZ() - These two functions were added to cope with the demand of the new fishing algorithms, wich are now able to correctly place the hook in the water, thanks to getHighZ(). getLowZ() was added, considering potential future modifications.
	@Override
	public int getLowZ()
	{
		return _z1;
	}
	
	@Override
	public int getHighZ()
	{
		return _z2;
	}
	
	@Override
	public void visualizeZone(int z)
	{
		final int count = (int) ((2 * Math.PI * _rad) / STEP);
		final double angle = (2 * Math.PI) / count;
		for (int i = 0; i < count; i++)
		{
			dropDebugItem(Inventory.ADENA_ID, 1, _x + (int) (Math.cos(angle * i) * _rad), _y + (int) (Math.sin(angle * i) * _rad), z);
		}
	}
	
	@Override
	public Location getRandomPoint()
	{
		int x = 0;
		int y = 0;
		final int x2 = _x - _rad;
		final int y2 = _y - _rad;
		final int x3 = _x + _rad;
		final int y3 = _y + _rad;
		double dx = _x - x;
		double dy = _y - y;
		while (((dx * dx) + (dy * dy)) > _radS)
		{
			x = Rnd.get(x2, x3);
			y = Rnd.get(y2, y3);
			dx = _x - x;
			dy = _y - y;
		}
		
		return new Location(x, y, GeoEngine.getInstance().getHeight(x, y, (_z1 + _z2) / 2));
	}
	
	@Override
	public Location getCenterPoint()
	{
		return _centerPoint;
	}
}
