/**
 * Copyright (C) 2007 Aelitis, All Rights Reserved.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place - Suite 330, Boston, MA 02111-1307, USA.
 *
 * AELITIS, SAS au capital de 63.529,40 euros
 * 8 Allee Lenotre, La Grille Royale, 78600 Le Mesnil le Roi, France.
 *
 */

package org.gudy.azureus2.ui.swt.views.piece;

import java.util.Arrays;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.*;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.*;
import org.gudy.azureus2.core3.disk.DiskManagerPiece;
import org.gudy.azureus2.core3.download.DownloadManager;
import org.gudy.azureus2.core3.peer.PEPeerManager;
import org.gudy.azureus2.ui.swt.mainwindow.Colors;
import org.gudy.azureus2.ui.swt.views.AbstractIView;
import org.gudy.azureus2.ui.swt.views.utils.CoordinateTransform;

import com.aelitis.azureus.core.peermanager.piecepicker.PiecePicker;


/**
 * @author TuxPaper
 * @created Feb 26, 2007
 *
 */
public class PieceDistributionView
	extends AbstractIView
{
	private Composite comp;
	private Canvas pieceDistCanvas;
	private GC pieceDistGC;
	private Image img;
	private DownloadManager dlm;
	private boolean initialized = false;

	public PieceDistributionView() {

	}

	public void dataSourceChanged(Object newDataSource) {
		if (newDataSource instanceof DownloadManager) {
			dlm = (DownloadManager)newDataSource;
			updateDistribution();
		}
	}

	/* (non-Javadoc)
	 * @see org.gudy.azureus2.ui.swt.views.AbstractIView#getData()
	 */
	public String getData() {
		return "PiecesView.DistributionView.title";
	}

	/* (non-Javadoc)
	 * @see org.gudy.azureus2.ui.swt.views.AbstractIView#initialize(org.eclipse.swt.widgets.Composite)
	 */
	public void initialize(Composite parent) {
		comp = new Composite(parent,SWT.NONE);
		createPieceDistPanel();
		initialized = true;
		updateDistribution();
	}

	private void createPieceDistPanel() {
		comp.setLayout(new FillLayout());
		//pieceDistComposite = new Composite(parent, SWT.NONE);
		pieceDistCanvas = new Canvas(comp,SWT.NONE);
		pieceDistGC = new GC(pieceDistCanvas);
	}

	public void updateDistribution() {
		if(!initialized || dlm == null || dlm.getPeerManager() == null || dlm.getPeerManager().getPiecePicker() == null)
			return;
		Rectangle rect = pieceDistCanvas.getBounds();
		if(rect.height <= 0 || rect.width <= 0)
			return;
		if(img != null)
			img.dispose();
	
		PiecePicker picker = dlm.getPeerManager().getPiecePicker();
		
		final int seeds = dlm.getPeerManager().getNbSeeds();
		final int connected = dlm.getPeerManager().getNbPeers() + seeds;
		final int upperBound = 1+(1<<(int)Math.ceil(Math.log(connected+0.0)/Math.log(2.0)));
		//System.out.println("conn:"+connected+" bound:"+upperBound);
		final int minAvail = (int)picker.getMinAvailability();
		final int maxAvail = picker.getMaxAvailability();
		final int nbPieces = picker.getNumberOfPieces();
		final int[] availabilties = picker.getAvailability();
		final DiskManagerPiece[] dmPieces = dlm.getDiskManager().getPieces();
		final int[] piecesPerAvailability = new int[upperBound];
		final int[] ourPiecesPerAvailability = new int[upperBound];
		
		int avlPeak = 0;
		int avlPeakIdx = -1;
		
		for(int i=0;i<nbPieces;i++)
		{
			if(availabilties[i] >= upperBound) 
				return; // availability lists and peer lists are OOS, just wait for the next round
			final int newPeak;
			if(avlPeak < (newPeak = ++piecesPerAvailability[availabilties[i]]))
			{
				 avlPeak = newPeak;
				 avlPeakIdx = availabilties[i];
			}
			if(dmPieces[i].isDone())
				++ourPiecesPerAvailability[availabilties[i]];
		}
		
		img = new Image(pieceDistGC.getDevice(),pieceDistCanvas.getBounds());
		
		GC gc = new GC(img);
		Transform scaler = new Transform(gc.getDevice());

		try
		{
			//System.out.println("bound:"+upperBound+" peak:"+avlPeak);
			
			scaler.translate(0,rect.height);
			scaler.scale(1F, -1F);
			scaler.scale(1F*rect.width/(upperBound), 1F*(rect.height-1F)/(avlPeak+1F));
			gc.setTransform(scaler);
			
			gc.setForeground(Colors.green);
			for(int i=0;i<connected;i++)
			{
				Color curColor;
				if(i<seeds)
					curColor = Colors.green;
				else
					curColor = Colors.blue;
				if(i==minAvail)
					curColor = Colors.red;
				gc.setBackground(curColor);
				gc.setForeground(curColor);
				gc.drawLine(i, 0, (i+1), 0);
				gc.drawRectangle(i, 0, 1, piecesPerAvailability[i]);
				gc.fillRectangle(i, 0, 1, ourPiecesPerAvailability[i]);
				
			}
			
			gc.setTransform(null);
			
			CoordinateTransform t = new CoordinateTransform(rect);
			
			t.shiftExternal(rect.width,0);
			t.scale(-1.0, 1.0);
			
			gc.setForeground(Colors.green);
			gc.setBackground(Colors.background);
			int charWidth = gc.getFontMetrics().getAverageCharWidth();
			int charHeight = gc.getFontMetrics().getHeight();
			
			String[] boxContent = new String[] {
				"Seed Avl Contribution",
				"Peer Avl Contribution",
				"Rarest Pieces: "+piecesPerAvailability[minAvail]+" (Avl:"+minAvail+")"
				};
			
			int maxBoxWidth = 0;
			int maxBoxOffset = 0;
			for(int i=0;i<boxContent.length;i++)
				maxBoxWidth = Math.max(maxBoxWidth, boxContent[i].length());
			
			maxBoxOffset = (maxBoxWidth+5) * charWidth;
			maxBoxWidth = ++maxBoxWidth * charWidth;
			
		
			gc.drawRectangle(t.x(maxBoxOffset),t.y(charHeight*1),maxBoxWidth,charHeight);
			gc.drawString(boxContent[0],t.x(maxBoxOffset-5),t.y(charHeight*1),true);
			
			gc.setForeground(Colors.blue);
			gc.drawRectangle(t.x(maxBoxOffset),t.y(charHeight*3),maxBoxWidth,charHeight);
			gc.drawString(boxContent[1],t.x(maxBoxOffset-5),t.y(charHeight*3),true);
			
			gc.setForeground(Colors.red);
			gc.drawRectangle(t.x(maxBoxOffset),t.y(charHeight*5),maxBoxWidth,charHeight);
			gc.drawString(boxContent[2],t.x(maxBoxOffset-5),t.y(charHeight*5),true);

			
		} finally
		{
			gc.dispose();
		}

		
		
		
	}

	public void refresh() {
		super.refresh();
		if(!initialized)
			return;
		updateDistribution();
		if(img != null && !img.isDisposed())
			pieceDistGC.drawImage(img, 0, 0);

	}

	/* (non-Javadoc)
	 * @see org.gudy.azureus2.ui.swt.views.AbstractIView#getComposite()
	 */
	public Composite getComposite() {
		return comp;
	}

	/* (non-Javadoc)
	 * @see org.gudy.azureus2.ui.swt.views.AbstractIView#delete()
	 */
	public void delete() {
		if(!initialized)
			return;
		initialized = false;
		super.delete();
		comp.dispose();
		pieceDistCanvas.dispose();
		pieceDistGC.dispose();
		if(img != null && !img.isDisposed())
			img.dispose();
	}
}
