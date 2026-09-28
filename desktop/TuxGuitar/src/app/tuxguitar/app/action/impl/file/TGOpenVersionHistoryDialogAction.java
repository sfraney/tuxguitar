package app.tuxguitar.app.action.impl.file;

import java.io.File;

import app.tuxguitar.action.TGActionContext;
import app.tuxguitar.action.TGActionManager;
import app.tuxguitar.app.action.impl.view.TGOpenViewAction;
import app.tuxguitar.app.document.TGDocumentFileManager;
import app.tuxguitar.app.view.dialog.version.TGVersionHistoryDialog;
import app.tuxguitar.app.view.dialog.version.TGVersionHistoryDialogController;
import app.tuxguitar.editor.action.TGActionBase;
import app.tuxguitar.io.history.TGSongVersionStore;
import app.tuxguitar.util.TGContext;
import app.tuxguitar.util.error.TGErrorManager;

public class TGOpenVersionHistoryDialogAction extends TGActionBase {

	public static final String NAME = "action.file.version-history";

	public TGOpenVersionHistoryDialogAction(TGContext context) {
		super(context, NAME);
	}

	protected void processAction(TGActionContext context){
		try {
			TGDocumentFileManager fileManager = TGDocumentFileManager.getInstance(getContext());
			if( !fileManager.isLocalFile() ) {
				return;
			}

			File songFile = new File(fileManager.getCurrentURI());
			if( TGSongVersionStore.listVersions(songFile).isEmpty() ) {
				return;
			}

			context.setAttribute(TGVersionHistoryDialog.ATTRIBUTE_SONG_FILE, songFile);
			context.setAttribute(TGOpenViewAction.ATTRIBUTE_CONTROLLER, new TGVersionHistoryDialogController());
			TGActionManager.getInstance(getContext()).execute(TGOpenViewAction.NAME, context);
		} catch (Throwable e) {
			TGErrorManager.getInstance(getContext()).handleError(e);
		}
	}
}
