package app.tuxguitar.app.view.dialog.version;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import app.tuxguitar.app.TuxGuitar;
import app.tuxguitar.app.action.impl.view.TGOpenViewAction;
import app.tuxguitar.app.action.listener.error.TGActionErrorHandler;
import app.tuxguitar.app.document.TGDocument;
import app.tuxguitar.app.document.TGDocumentListAttributes;
import app.tuxguitar.app.document.TGDocumentListManager;
import app.tuxguitar.app.ui.TGApplication;
import app.tuxguitar.app.util.TGMessageDialogUtil;
import app.tuxguitar.app.view.controller.TGViewContext;
import app.tuxguitar.app.view.dialog.confirm.TGConfirmDialog;
import app.tuxguitar.app.view.dialog.confirm.TGConfirmDialogController;
import app.tuxguitar.app.view.util.TGDialogUtil;
import app.tuxguitar.editor.action.TGActionProcessor;
import app.tuxguitar.editor.action.file.TGReadSongAction;
import app.tuxguitar.io.base.TGFileFormatUtils;
import app.tuxguitar.io.history.TGSongVersion;
import app.tuxguitar.io.history.TGSongVersionStore;
import app.tuxguitar.ui.UIFactory;
import app.tuxguitar.ui.event.UISelectionEvent;
import app.tuxguitar.ui.event.UISelectionListener;
import app.tuxguitar.ui.layout.UITableLayout;
import app.tuxguitar.ui.widget.UIButton;
import app.tuxguitar.ui.widget.UIPanel;
import app.tuxguitar.ui.widget.UITable;
import app.tuxguitar.ui.widget.UITableItem;
import app.tuxguitar.ui.widget.UIWindow;
import app.tuxguitar.util.TGContext;
import app.tuxguitar.util.error.TGErrorHandler;
import app.tuxguitar.util.error.TGErrorManager;

public class TGVersionHistoryDialog {

	public static final String ATTRIBUTE_SONG_FILE = "songFile";

	private static final float TABLE_WIDTH = 400;
	private static final float TABLE_HEIGHT = 250;

	public void show(final TGViewContext viewContext) {
		final TGContext context = viewContext.getContext();
		final File songFile = viewContext.getAttribute(ATTRIBUTE_SONG_FILE);
		final UIFactory uiFactory = TGApplication.getInstance(context).getFactory();
		final UIWindow uiParent = viewContext.getAttribute(TGViewContext.ATTRIBUTE_PARENT);
		final UITableLayout dialogLayout = new UITableLayout();
		final UIWindow dialog = uiFactory.createWindow(uiParent, true, true);

		dialog.setLayout(dialogLayout);
		dialog.setText(TuxGuitar.getProperty("file.version-history"));

		UITable<Integer> table = uiFactory.createTable(dialog, true);
		table.setColumns(2);
		table.setColumnName(0, TuxGuitar.getProperty("version.column.number"));
		table.setColumnName(1, TuxGuitar.getProperty("version.column.date"));
		addVersions(table, songFile);

		dialogLayout.set(table, 1, 1, UITableLayout.ALIGN_FILL, UITableLayout.ALIGN_FILL, true, true);
		dialogLayout.set(table, UITableLayout.PACKED_WIDTH, TABLE_WIDTH);
		dialogLayout.set(table, UITableLayout.PACKED_HEIGHT, TABLE_HEIGHT);

		//------------------BUTTONS--------------------------
		UITableLayout buttonsLayout = new UITableLayout(0f);
		UIPanel buttons = uiFactory.createPanel(dialog, false);
		buttons.setLayout(buttonsLayout);
		dialogLayout.set(buttons, 2, 1, UITableLayout.ALIGN_RIGHT, UITableLayout.ALIGN_FILL, true, false);

		final UIButton buttonRestore = uiFactory.createButton(buttons);
		buttonRestore.setText(TuxGuitar.getProperty("version.restore"));
		buttonRestore.setEnabled(false);
		buttonRestore.addSelectionListener(new UISelectionListener() {
			public void onSelect(UISelectionEvent event) {
				Integer version = table.getSelectedValue();
				if( version != null ) {
					restore(context, dialog, uiParent, songFile, version.intValue());
				}
			}
		});
		buttonsLayout.set(buttonRestore, 1, 1, UITableLayout.ALIGN_FILL, UITableLayout.ALIGN_FILL, true, true, 1, 1, 90f, 25f, null);

		UIButton buttonClose = uiFactory.createButton(buttons);
		buttonClose.setText(TuxGuitar.getProperty("close"));
		buttonClose.setDefaultButton();
		buttonClose.addSelectionListener(new UISelectionListener() {
			public void onSelect(UISelectionEvent event) {
				dialog.dispose();
			}
		});
		buttonsLayout.set(buttonClose, 1, 2, UITableLayout.ALIGN_FILL, UITableLayout.ALIGN_FILL, true, true, 1, 1, 90f, 25f, null);
		buttonsLayout.set(buttonClose, UITableLayout.MARGIN_RIGHT, 0f);

		table.addSelectionListener(new UISelectionListener() {
			public void onSelect(UISelectionEvent event) {
				buttonRestore.setEnabled(table.getSelectedValue() != null);
			}
		});

		TGDialogUtil.openDialog(dialog, TGDialogUtil.OPEN_STYLE_CENTER | TGDialogUtil.OPEN_STYLE_PACK);
		dialog.setMinimumSize((int) dialog.getBounds().getWidth(), (int) dialog.getBounds().getHeight());
	}

	private void addVersions(UITable<Integer> table, File songFile) {
		List<TGSongVersion> versions = TGSongVersionStore.listVersions(songFile);
		// most recent first
		for (int i = versions.size() - 1; i >= 0; i--) {
			TGSongVersion version = versions.get(i);
			Integer number = Integer.valueOf(version.getNumber());

			UITableItem<Integer> item = new UITableItem<Integer>(number);
			item.setText(0, number.toString());
			item.setText(1, version.getTimestamp());
			table.addItem(item);
		}
	}

	private void restore(final TGContext context, final UIWindow dialog, final UIWindow parent, final File songFile, final int version) {
		TGDocumentListManager documentListManager = TGDocumentListManager.getInstance(context);
		TGDocument currentDocument = documentListManager.findCurrentDocument();
		if( currentDocument == null || !currentDocument.isUnsaved() ) {
			dialog.dispose();
			doRestore(context, parent, songFile, version);
			return;
		}

		// the restore replaces the song in memory, ask before dropping the changes
		TGActionProcessor tgActionProcessor = new TGActionProcessor(context, TGOpenViewAction.NAME);
		tgActionProcessor.setAttribute(TGOpenViewAction.ATTRIBUTE_CONTROLLER, new TGConfirmDialogController());
		tgActionProcessor.setAttribute(TGViewContext.ATTRIBUTE_PARENT, parent);
		tgActionProcessor.setAttribute(TGConfirmDialog.ATTRIBUTE_MESSAGE, TuxGuitar.getProperty("version.restore.confirm", new String[] { String.valueOf(version) }));
		tgActionProcessor.setAttribute(TGConfirmDialog.ATTRIBUTE_STYLE, TGConfirmDialog.BUTTON_YES | TGConfirmDialog.BUTTON_CANCEL);
		tgActionProcessor.setAttribute(TGConfirmDialog.ATTRIBUTE_DEFAULT_BUTTON, TGConfirmDialog.BUTTON_CANCEL);
		tgActionProcessor.setAttribute(TGConfirmDialog.ATTRIBUTE_RUNNABLE_YES, new Runnable() {
			public void run() {
				dialog.dispose();
				doRestore(context, parent, songFile, version);
			}
		});
		tgActionProcessor.process();
	}

	private void doRestore(final TGContext context, UIWindow parent, final File songFile, final int version) {
		InputStream stream;
		try {
			TGSongVersionStore.restore(songFile, version);
			stream = new FileInputStream(songFile);
		} catch (IOException e) {
			TGMessageDialogUtil.errorMessage(context, parent, TuxGuitar.getProperty("version.error.restore", new String[] { String.valueOf(version) }));
			return;
		}

		// the restored content replaces the song in memory, so the current document is
		// dropped and re-read from disk. TGReadURLAction cannot be used here: it reuses
		// the song already loaded for an URI that is open.
		final TGDocument currentDocument = TGDocumentListManager.getInstance(context).findCurrentDocument();
		final boolean wasUnsaved = (currentDocument != null && currentDocument.isUnsaved());
		if( currentDocument != null ) {
			// an unwanted document is only removed once it has no unsaved changes
			currentDocument.setUnwanted(true);
			currentDocument.setUnsaved(false);
		}

		TGActionProcessor tgActionProcessor = new TGActionProcessor(context, TGReadSongAction.NAME);
		tgActionProcessor.setAttribute(TGReadSongAction.ATTRIBUTE_INPUT_STREAM, stream);
		tgActionProcessor.setAttribute(TGReadSongAction.ATTRIBUTE_FORMAT_CODE, TGFileFormatUtils.getFileFormatCode(songFile.getName()));
		tgActionProcessor.setAttribute(TGDocumentListAttributes.ATTRIBUTE_DOCUMENT_URI, songFile.toURI());
		tgActionProcessor.setAttribute(TGActionErrorHandler.ATTRIBUTE_ERROR_HANDLER, new TGErrorHandler() {
			public void handleError(Throwable throwable) {
				if( currentDocument != null ) {
					// the song was not reloaded, keep the document in the state it had
					// before the restore
					currentDocument.setUnwanted(false);
					currentDocument.setUnsaved(wasUnsaved);
				}
				TGErrorManager.getInstance(context).handleError(throwable);
			}
		});
		tgActionProcessor.process();
	}
}
