package app.tuxguitar.app.view.dialog.version;

import app.tuxguitar.app.view.controller.TGOpenViewController;
import app.tuxguitar.app.view.controller.TGViewContext;

public class TGVersionHistoryDialogController implements TGOpenViewController {

	public void openView(TGViewContext context) {
		new TGVersionHistoryDialog().show(context);
	}
}
