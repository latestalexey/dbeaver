/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2017 Serge Rider (serge@jkiss.org)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jkiss.dbeaver.ui.controls.txn;

import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.DisposeEvent;
import org.eclipse.swt.events.DisposeListener;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.*;
import org.eclipse.ui.menus.WorkbenchWindowControlContribution;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.Log;
import org.jkiss.dbeaver.core.DBeaverCore;
import org.jkiss.dbeaver.core.DBeaverUI;
import org.jkiss.dbeaver.model.DBPContextProvider;
import org.jkiss.dbeaver.model.exec.DBCExecutionContext;
import org.jkiss.dbeaver.model.exec.DBCSavepoint;
import org.jkiss.dbeaver.model.exec.DBCStatement;
import org.jkiss.dbeaver.model.qm.QMUtils;
import org.jkiss.dbeaver.model.qm.meta.QMMSessionInfo;
import org.jkiss.dbeaver.model.qm.meta.QMMStatementExecuteInfo;
import org.jkiss.dbeaver.model.qm.meta.QMMTransactionInfo;
import org.jkiss.dbeaver.model.qm.meta.QMMTransactionSavepointInfo;
import org.jkiss.dbeaver.model.runtime.DefaultProgressMonitor;
import org.jkiss.dbeaver.runtime.qm.DefaultExecutionHandler;
import org.jkiss.dbeaver.ui.IActionConstants;
import org.jkiss.dbeaver.ui.UIUtils;
import org.jkiss.dbeaver.ui.perspective.AbstractPartListener;

/**
 * DataSource Toolbar
 */
public class TransactionMonitorToolbar {
    private static final Log log = Log.getLog(TransactionMonitorToolbar.class);

    private IWorkbenchWindow workbenchWindow;

    public TransactionMonitorToolbar(IWorkbenchWindow workbenchWindow) {
        this.workbenchWindow = workbenchWindow;
    }

    private Control createControl(Composite parent) {
        final MonitorPanel monitorPanel = new MonitorPanel(parent);

        final IPartListener partListener = new AbstractPartListener() {
            @Override
            public void partActivated(IWorkbenchPart part) {
                monitorPanel.refresh();
            }

            @Override
            public void partDeactivated(IWorkbenchPart part) {
                monitorPanel.refresh();
            }
        };
        final IWorkbenchPage activePage = this.workbenchWindow.getActivePage();
        activePage.addPartListener(partListener);
        monitorPanel.addDisposeListener(new DisposeListener() {
            @Override
            public void widgetDisposed(DisposeEvent e) {
                activePage.removePartListener(partListener);
            }
        });

        return monitorPanel;
    }

    private class RefreshJob extends Job {
        private final MonitorPanel monitorPanel;

        public RefreshJob(MonitorPanel monitorPanel) {
            super("Refresh transaction monitor");
            this.monitorPanel = monitorPanel;
        }

        @Override
        protected IStatus run(final IProgressMonitor monitor) {
            monitorPanel.updateTransactionsInfo(new DefaultProgressMonitor(monitor));
            return Status.OK_STATUS;
        }
    }

    private class MonitorPanel extends Composite {

        private QMEventsHandler qmHandler;
        private final Text txnText;
        private RefreshJob refreshJob;

        public MonitorPanel(Composite parent) {
            super(parent, SWT.NONE);
            //setBackground(parent.getDisplay().getSystemColor(SWT.COLOR_WIDGET_BACKGROUND));
            setCursor(parent.getDisplay().getSystemCursor(SWT.CURSOR_HAND));
            setToolTipText("Transactions monitor");
            addPaintListener(new PaintListener() {
                @Override
                public void paintControl(PaintEvent e) {
                    paint(e);
                }
            });
            GridLayout layout = new GridLayout(1, false);
            layout.marginHeight = 0;
            //layout.marginWidth = 0;
            setLayout(layout);

            txnText = new Text(this, SWT.BORDER | SWT.SINGLE);
            txnText.setEnabled(false);
            txnText.setBackground(parent.getDisplay().getSystemColor(SWT.COLOR_WIDGET_BACKGROUND));
            txnText.setCursor(parent.getDisplay().getSystemCursor(SWT.CURSOR_HAND));
            txnText.setText("");
            txnText.setToolTipText("Transactions monitor");
            GridData gd = new GridData();
            gd.verticalAlignment = GridData.CENTER;
            gd.grabExcessVerticalSpace = true;
            gd.widthHint = UIUtils.getFontHeight(txnText) * 6;
            txnText.setLayoutData(gd);

            qmHandler = new QMEventsHandler(this);
            QMUtils.registerHandler(qmHandler);

            addDisposeListener(new DisposeListener() {
                @Override
                public void widgetDisposed(DisposeEvent e) {
                    QMUtils.unregisterHandler(qmHandler);
                    qmHandler = null;
                }
            });

            refreshJob = new RefreshJob(this);
        }

        private void paint(PaintEvent e) {
            //e.gc.drawRectangle(e.x, e.y, e.width, e.height);
        }

        @Override
        public Point computeSize(int wHint, int hHint, boolean changed) {
            return super.computeSize(wHint, hHint, changed);
        }

        public void refresh() {
            refreshJob.schedule(500);
        }

        public void updateTransactionsInfo(DefaultProgressMonitor monitor) {
            monitor.beginTask("Extract active transaction info", 1);

            DBCExecutionContext executionContext = null;
            final IEditorPart activeEditor = workbenchWindow.getActivePage().getActiveEditor();
            if (activeEditor instanceof DBPContextProvider) {
                executionContext = ((DBPContextProvider) activeEditor).getExecutionContext();
            }
            if (executionContext == null) {
                System.out.println("REFRESH MONITOR -> EMPTY");
            } else {
                System.out.println("REFRESH MONITOR [" + executionContext.getContextName() + "]");
                QMMSessionInfo sessionInfo = DBeaverCore.getInstance().getQueryManager().getMetaCollector().getSessionInfo(executionContext);
                QMMTransactionInfo txnInfo = sessionInfo.getTransaction();
                if (txnInfo != null) {
                    QMMTransactionSavepointInfo sp = txnInfo.getCurrentSavepoint();
                    QMMStatementExecuteInfo execInfo = sp.getLastExecute();
                    int execCount = 0, updateCount = 0;
                    for (QMMStatementExecuteInfo exec = execInfo; exec != null ;exec = exec.getPrevious()) {
                        execCount++;
                    }
                    System.out.println("\tTXN=" + sp.getName() + " (" + updateCount + "/" + execCount + ")");
                }
            }

            monitor.done();
            // Update UI
            DBeaverUI.asyncExec(new Runnable() {
                @Override
                public void run() {
                    redraw();
                }
            });
        }
    }

    public static class ToolbarContribution extends WorkbenchWindowControlContribution {
        public ToolbarContribution() {
            super(IActionConstants.TOOLBAR_TXN);
        }

        @Override
        protected Control createControl(Composite parent) {
            TransactionMonitorToolbar toolbar = new TransactionMonitorToolbar(DBeaverUI.getActiveWorkbenchWindow());
            return toolbar.createControl(parent);
        }
    }

    private static class QMEventsHandler extends DefaultExecutionHandler {
        private final MonitorPanel monitorPanel;

        QMEventsHandler(MonitorPanel monitorPanel) {
            this.monitorPanel = monitorPanel;
        }

        @NotNull
        @Override
        public String getHandlerName() {
            return QMEventsHandler.class.getName();
        }

        private void refreshMonitor() {
            if (!monitorPanel.isDisposed()) {
                monitorPanel.refresh();
            }
        }

        @Override
        public synchronized void handleTransactionAutocommit(@NotNull DBCExecutionContext context, boolean autoCommit)
        {
            refreshMonitor();
        }

        @Override
        public synchronized void handleTransactionCommit(@NotNull DBCExecutionContext context)
        {
            refreshMonitor();
        }

        @Override
        public synchronized void handleTransactionRollback(@NotNull DBCExecutionContext context, DBCSavepoint savepoint)
        {
            refreshMonitor();
        }

        @Override
        public synchronized void handleStatementExecuteBegin(@NotNull DBCStatement statement)
        {
            refreshMonitor();
        }
    }

}