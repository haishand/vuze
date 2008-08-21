/**
 * 
 */
package com.aelitis.azureus.ui.swt.skin;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.widgets.*;

/**
 * @author TuxPaper
 * @created Jun 8, 2006
 *
 */
public class SWTSkinImageChanger
	implements Listener
{
	private final static boolean DEBUG = false;

	private final String suffix;

	private final int eventOn;

	private final int eventOff;

	private Control lastControl;

	/**
	 * Default Constructor
	 * 
	 * @param suffix
	 * @param eventOff 
	 * @param eventOn 
	 */
	public SWTSkinImageChanger(String suffix, int eventOn, int eventOff) {
		this.suffix = suffix;
		this.eventOn = eventOn;
		this.eventOff = eventOff;
	}

	public void handleEvent(Event event) {
		Control control = (Control) event.widget;

		if (control == null) {
			return;
		}

		try {
			boolean isExit = event.type == SWT.MouseExit
					|| event.type == SWT.Deactivate;
			if (isExit && lastControl != null) {
				if (control.getParent() == lastControl) {
					if (DEBUG) {
						System.out.println("skip because parent is last control");
					}
					return;
				}
			}

			SWTSkinObject skinObject = (SWTSkinObject) control.getData("SkinObject");
			if (DEBUG) {
				System.out.println("exit " + skinObject);
			}

			if (isExit && (skinObject instanceof SWTSkinObjectContainer)) {
				// check if exiting and going into child
				SWTSkinObjectContainer soContainer = (SWTSkinObjectContainer) skinObject;
				if (soContainer.getPropogation()) {
					Point pt = control.toDisplay(event.x, event.y);
					Composite composite = soContainer.getComposite();
					Point relPt = composite.toControl(pt);
					// mouse exit and enter happens on client area (not full widget area)
					Rectangle bounds = composite.getClientArea();
					if (bounds.contains(relPt)
							&& composite.getDisplay().getActiveShell() != null) {
						if (DEBUG) {
							System.out.println("skip " + skinObject
									+ " because going into child");
						}
						return;
					}
				}
			}

			if (isExit && control.getParent() != null) {
				// check if exiting and going into parent
				Composite parent = control.getParent();
				SWTSkinObject soParent = (SWTSkinObject) parent.getData("SkinObject");
				if (soParent != null && (soParent instanceof SWTSkinObjectContainer)) {
					SWTSkinObjectContainer container = (SWTSkinObjectContainer) soParent;
					if (container.getPropogation()) {
						Point pt = control.toDisplay(event.x, event.y);
						Point relPt = container.getComposite().toControl(pt);
						Rectangle bounds = parent.getClientArea();
						if (bounds.contains(relPt)
								&& parent.getDisplay().getActiveShell() != null) {
							if (DEBUG) {
								System.out.println("skip " + skinObject
										+ " because going into parent");
							}
							return;
						}
					}
				}
			}

			if (skinObject != null) {
				String sSuffix = (event.type == eventOn) ? suffix : "";
				if (DEBUG) {
					System.out.println(System.currentTimeMillis() + ": " + skinObject
							+ "--" + sSuffix);
				}

				skinObject.switchSuffix(sSuffix, 2, true);
			}
		} finally {
			lastControl = control;
		}
	}
}
