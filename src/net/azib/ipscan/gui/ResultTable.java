/**
 * This file is a part of Angry IP Scanner source code,
 * see http://www.azib.net/ for more information.
 * Licensed under GPLv2.
 */
package net.azib.ipscan.gui;

import java.util.List;

import net.azib.ipscan.config.Config;
import net.azib.ipscan.config.Labels;
import net.azib.ipscan.core.ScanningResult;
import net.azib.ipscan.core.ScanningResultList;
import net.azib.ipscan.core.ScanningResult.ResultType;
import net.azib.ipscan.core.state.StateMachine;
import net.azib.ipscan.fetchers.Fetcher;
import net.azib.ipscan.fetchers.FetcherRegistry;
import net.azib.ipscan.fetchers.FetcherRegistryUpdateListener;
import net.azib.ipscan.gui.actions.ColumnsActions;
import net.azib.ipscan.gui.actions.CommandsActions;
import net.azib.ipscan.gui.actions.ToolsActions;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Event;
import org.eclipse.swt.widgets.Listener;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;

/**
 * Table of scanning results.
 * 
 * @author Anton Keks
 */
public class ResultTable extends Table implements FetcherRegistryUpdateListener {
	
	private ScanningResultList scanningResults;
	
	private Image[] listImages = new Image[ResultType.values().length];

	private Listener columnClickListener;

	private Listener columnResizeListener;

	public ResultTable(Composite parent, FetcherRegistry fetcherRegistry, ScanningResultList scanningResultList, StateMachine stateMachine, ColumnsActions.ColumnClick columnClickListener, ColumnsActions.ColumnResize columnResizeListener, ToolsActions.TableSelection selectionListener) {
		super(parent, SWT.BORDER | SWT.MULTI | SWT.FULL_SELECTION | SWT.VIRTUAL);
		this.scanningResults = scanningResultList;
		
		setHeaderVisible(true);
		setLinesVisible(true);
		
		this.columnClickListener = columnClickListener;
		this.columnResizeListener = columnResizeListener;
		fetcherRegistry.addListener(this);
		handleUpdateOfSelectedFetchers(fetcherRegistry);
		
		// load button images
		listImages[ResultType.UNKNOWN.ordinal()] = new Image(null, Labels.getInstance().getImageAsStream("list.unknown.img"));
		listImages[ResultType.DEAD.ordinal()] = new Image(null, Labels.getInstance().getImageAsStream("list.dead.img"));
		listImages[ResultType.ALIVE.ordinal()] = new Image(null, Labels.getInstance().getImageAsStream("list.alive.img"));
		listImages[ResultType.WITH_PORTS.ordinal()] = new Image(null, Labels.getInstance().getImageAsStream("list.addinfo.img"));
		
		Listener detailsListener = new DetailsListener();
		addListener(SWT.Traverse, detailsListener);
		addListener(SWT.MouseDoubleClick, detailsListener);
		addListener(SWT.Selection, selectionListener);
		addListener(SWT.KeyDown, new CommandsActions.Delete(this, stateMachine));
		addListener(SWT.KeyDown, new CommandsActions.CopyIP(this));
		
		// this one populates table dynamically, taking data from ScanningResultList
		addListener(SWT.SetData, new SetDataListener());
	}

	/**
	 * Rebuild column list according to selected fetchers
	 */
	public void handleUpdateOfSelectedFetchers(FetcherRegistry fetcherRegistry) {
		// remove all items (otherwise they will be shown incorrectly)
		removeAll();
		
		// remove all columns
		TableColumn[] columns = getColumns();
		for (int i = 0; i < columns.length; i++) {
			columns[i].dispose();
		}
		
		// add the new selected columns back
		for (Fetcher fetcher : fetcherRegistry.getSelectedFetchers()) {
			TableColumn tableColumn = new TableColumn(this, SWT.NONE);
			String fetcherName = Labels.getLabel(fetcher.getLabel());
			tableColumn.setWidth(Config.getDimensionsConfig().getColumnWidth(fetcherName));
			tableColumn.setText(fetcherName);
			tableColumn.setData(fetcher);	// this is used in some listeners in ColumnsActions
			tableColumn.addListener(SWT.Selection, columnClickListener);
			tableColumn.addListener(SWT.Resize, columnResizeListener);
		}
	}

	protected void checkSubclass() {
		// This method is overriden and does nothing in order to
		// be able to subclass the Table. We are not going to 
		// override anything important, so this should be safe (tm)
	}

	/**
	 * Adds the specified results holder to the table and registers it
	 * in the ScanningResultList instance or just redraws the corresponding row
	 * if the result is already present.
	 * <p/>
	 * Note: this method may be called from any thread.
	 * 
	 * @param result
	 */
	public void addOrUpdateResultRow(final ScanningResult result) {
		if (isDisposed())
			return;
		getDisplay().syncExec(new Runnable() {
			public void run() {
				if (scanningResults.isRegistered(result)) {
					// just redraw the item
					int index = scanningResults.update(result);
					clear(index);
				}
				else {
					// first register, then add - otherwise first redraw may fail (the table is virtual)
					int index = getItemCount();
					scanningResults.registerAtIndex(index, result);
					// setItemCount(index+1) - this seems to rebuild TableItems inside, so is slower
					new TableItem(ResultTable.this, SWT.NONE);
				}
			}
		});
	}
	
	/**
	 * Forces all elements to be redrawn
	 */
	public void updateResults() {
		clearAll();
	}
	
	/**
	 * Returns the details about the currently selected IP address
	 * @return
	 */
	public String getIPDetails() {
		int selectedIndex = getSelectionIndex();
		return scanningResults.getResultAsString(selectedIndex);
	}
	
	public void remove(int[] indices) {
		// we need to remove the elements from our real storage as well
		scanningResults.remove(indices);
		super.remove(indices);
	}
	
	public void removeAll() {
		// remove all items from the real storage first
		scanningResults.clear();
		super.removeAll();
	}
	
	/**
	 * Resets selected items as if they were just added to the table.
	 * This is used for removing of any scanned data for rescanning of items.
	 */
	public void resetSelection() {
		int[] selectionIndices = getSelectionIndices();
		// clear scanning results
		for (int itemNum : selectionIndices) {
			scanningResults.getResult(itemNum).reset();
		}
		// redraw items in the table
		clear(selectionIndices);
	}

	/**
	 * @return the internal ScanningResultList instance, containing the results.
	 */
	public ScanningResultList getScanningResults() {
		return scanningResults;
	}

	/**
	 * This listener shows the details window
	 */
	final class DetailsListener extends CommandsActions.Details {

		public DetailsListener() {
			super(ResultTable.this);
		}

		public void handleEvent(Event e) {
			// activate only if something is selected
			if (getSelectionIndex() >= 0 && (e.type == SWT.MouseDoubleClick || e.detail == SWT.TRAVERSE_RETURN)) {
				e.doit = false;
				super.handleEvent(e);					
			}
		}
	}

	/**
	 * This listener is used for displaying the real results in the table, on demand.
	 */
	final class SetDataListener implements Listener {

		public void handleEvent(Event event) {
			TableItem item = (TableItem)event.item;
			int tableIndex = indexOf(item);
			
			ScanningResult scanningResult = scanningResults.getResult(tableIndex);
			List<?> values = scanningResult.getValues();
			String[] resultStrings = new String[values.size()];
			for (int i = 0; i < values.size(); i++) {				
				Object value = values.get(i);
				if (value != null)
					resultStrings[i] = value.toString();
			}			 
			item.setText(resultStrings);
			item.setImage(0, listImages[scanningResult.getType().ordinal()]);
		}
		
	}

}
