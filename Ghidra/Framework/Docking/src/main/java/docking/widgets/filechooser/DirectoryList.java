/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
/*
 * Created on May 18, 2006
 */
package docking.widgets.filechooser;

import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.swing.*;
import javax.swing.event.ListDataEvent;
import javax.swing.event.ListDataListener;

import docking.event.mouse.GMouseListenerAdapter;
import docking.widgets.label.GDLabel;
import docking.widgets.list.GList;
import ghidra.util.exception.AssertException;

class DirectoryList extends GList<File> implements GhidraFileChooserDirectoryModelIf {
	private static final int DEFAULT_ICON_SIZE = 16;
	private static final int WIDTH_PADDING = 14;
	private static final int HEIGHT_PADDING = 5;

	private GhidraFileChooser chooser;
	private DirectoryListModel model;
	private FileListCellRenderer cellRenderer;
	private JLabel listEditorLabel;
	private JTextField listEditorField;
	private JPanel listEditor;

	/** The file being edited */
	private File editedFile;

	DirectoryList(GhidraFileChooser chooser, DirectoryListModel model) {
		super(model);
		this.chooser = chooser;
		this.model = model;
		build();
	}

	private void build() {

		setLayoutOrientation(JList.VERTICAL_WRAP);
		cellRenderer = new FileListCellRenderer(getFont(), chooser);
		setCellRenderer(cellRenderer);
		model.addListDataListener(new ListDataListener() {
			@Override
			public void contentsChanged(ListDataEvent e) {
				// called when the list changes because a new file is inserted (ie. create new folder action)
				recomputeListCellDimensions(null);
			}

			@Override
			public void intervalAdded(ListDataEvent e) {
				recomputeListCellDimensions(null);
			}

			@Override
			public void intervalRemoved(ListDataEvent e) {
				// don't care
			}
		});

		addMouseListener(new GMouseListenerAdapter() {
			@Override
			public void mouseClicked(MouseEvent e) {
				super.mouseClicked(e);

				// always end editing on a mouse click of any kind
				listEditor.setVisible(false);
				requestFocus();
			}

			@Override
			public boolean shouldConsume(MouseEvent e) {
				if (e.isPopupTrigger() && isEditing()) {
					return true;
				}
				return false;
			}

			@Override
			public void popupTriggered(MouseEvent e) {
				maybeSelectItem(e);
			}

			@Override
			public void doubleClickTriggered(MouseEvent e) {
				handleDoubleClick();
			}
		});

		addKeyListener(new KeyAdapter() {
			@Override
			public void keyReleased(KeyEvent e) {
				if (e.getKeyCode() != KeyEvent.VK_ENTER) {
					return;
				}
				e.consume();

				int[] selectedIndices = getSelectedIndices();
				if (selectedIndices.length == 0) {
					chooser.okCallback();
					// this implies the user has somehow put focus into the table, but has not
					// made a selection...just let the chooser decide what to do
					return;
				}

				if (selectedIndices.length > 1) {
					// let the chooser decide what to do with multiple rows selected
					chooser.okCallback();
					return;
				}

				File file = model.getFile(selectedIndices[0]);
				if (chooser.getModel().isDirectory(file)) {
					chooser.setCurrentDirectory(file);
				}
				else {
					chooser.userChoseFile(file);
				}
			}
		});

		addListSelectionListener(e -> {
			if (e.getValueIsAdjusting()) {
				return;
			}
			updateChooserForSelection();
		});

		listEditorLabel = new GDLabel();
		listEditorLabel.addMouseListener(new MouseAdapter() {
			@Override
			public void mouseReleased(MouseEvent e) {
				int index = locationToIndex(new Point(listEditor.getX(), listEditor.getY()));
				File file = model.getFile(index);
				if (e.getClickCount() == 2) {
					if (chooser.getModel().isDirectory(file)) {
						chooser.setCurrentDirectory(file);
					}
					cancelListEdit();
				}
			}
		});

		listEditorField = new JTextField();
		listEditorField.setName("LIST_EDITOR_FIELD");
		listEditorField.addKeyListener(new KeyAdapter() {
			@Override
			public void keyPressed(KeyEvent e) {
				if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
					cancelListEdit();
					e.consume();
				}
			}

			@Override
			public void keyReleased(KeyEvent e) {
				if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
					listEditor.setVisible(false);
					e.consume();
				}
				else if (e.getKeyCode() == KeyEvent.VK_ENTER) {
					String invalidFilenameMessage =
						chooser.getInvalidFilenameMessage(listEditorField.getText());
					if (invalidFilenameMessage != null) {
						chooser.setStatusText(invalidFilenameMessage);
						// keep the user in the field by not stopping the current edit
					}
					else {
						stopListEdit();
					}
					e.consume();
				}
			}
		});

		listEditorField.addFocusListener(new FocusAdapter() {
			@Override
			public void focusLost(FocusEvent e) {
				// Tracker SCR 3358 - Keep changes on focus lost
				stopListEdit();
			}
		});

		listEditor = new JPanel(new BorderLayout());
		listEditor.setBorder(BorderFactory.createLineBorder(Color.GRAY));

		listEditor.add(listEditorLabel, BorderLayout.WEST);
		listEditor.add(listEditorField, BorderLayout.CENTER);

		listEditor.setBackground(Color.WHITE);
		listEditorField.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));

		add(listEditor);
	}

	private void maybeSelectItem(MouseEvent e) {
		Point point = e.getPoint();
		int index = locationToIndex(point);
		if (index < 0) {
			return;
		}
		setSelectedIndex(index);
	}

	private void handleDoubleClick() {
		List<File> selectedFiles = new ArrayList<>();
		int[] selectedIndices = getSelectedIndices();
		for (int i : selectedIndices) {
			selectedFiles.add(model.getFile(i));
		}

		if (selectedFiles.size() == 0 || selectedFiles.size() > 1) {
			return; // not sure if this can happen, maybe with the Ctrl key pressed
		}

		File file = selectedFiles.get(0);
		if (chooser.getModel().isDirectory(file)) {
			chooser.setCurrentDirectory(file); // the user wants to navigate into the directory 
		}
		else {
			chooser.userChoseFile(file); // the user has chosen the file
		}
	}

	private void updateChooserForSelection() {
		List<File> selectedFiles = new ArrayList<>();
		int[] selectedIndices = getSelectedIndices();
		for (int index : selectedIndices) {
			selectedFiles.add(model.getFile(index));
		}
		chooser.userSelectedFiles(selectedFiles);
	}

	@Override
	public int[] getSelectedRows() {
		return getSelectedIndices();
	}

	@Override
	public File getSelectedFile() {
		int index = getSelectedIndex();
		if (index < 0) {
			return null;
		}
		return model.getFile(index);
	}

	@Override
	public File getFile(int row) {
		return model.getFile(row);
	}

	@Override
	public void edit() {
		int index = getSelectedIndex();
		editListCell(index);
	}

	@Override
	public void setSelectedFile(File file) {
		int[] selectedIndices = getSelectedIndices();
		if (selectedIndices.length == 1) {
			File selectedFile = model.getFile(selectedIndices[0]);
			if (selectedFile.equals(file)) {
				return; // selection hasn't changed; nothing to do
			}
		}

		for (int i = 0; i < model.getSize(); i++) {
			File aFile = model.getFile(i);
			if ((aFile != null) && aFile.equals(file)) {
				setSelectedIndex(i);
				Rectangle rect = getCellBounds(i, i);
				scrollRectToVisible(rect);
				return;
			}
		}
	}

	void setSelectedFiles(Iterable<File> files) {

		List<Integer> indexes = new ArrayList<>();
		for (File f : files) {
			indexes.add(model.indexOfFile(f));
		}

		int[] indices = new int[indexes.size()];
		for (int i = 0; i < indices.length; i++) {
			indices[i] = indexes.get(i);
		}

		setSelectedIndices(indices);
	}

	private boolean isEditing() {
		return (editedFile != null);
	}

	void editListCell(int index) {
		if (index == -1) {
			return;
		}
		add(listEditor);
		Rectangle r = getCellBounds(index, index);
		editedFile = model.getFile(index);
		if (editedFile == null) {
			throw new AssertException(
				"Unexpected condition - asked to edit file that " + "does not exist in model");
		}

		listEditor.setBounds(r.x, r.y, r.width, r.height);
		listEditor.setVisible(true);
		listEditorLabel.setIcon(chooser.getModel().getIcon(editedFile));
		listEditorField.setText(editedFile.getName());
		listEditorField.requestFocus();
		listEditorField.selectAll();
	}

	void cancelListEdit() {
		editedFile = null;
		remove(listEditor);
		listEditor.setVisible(false);
		listEditorLabel.setIcon(null);
		listEditorField.setText("");
		repaint();
	}

	void stopListEdit() {
		// this method can be called even when we are not editing
		if (!isEditing()) {
			return;
		}

		String invalidFilenameMessage =
			chooser.getInvalidFilenameMessage(listEditorField.getText());
		if (invalidFilenameMessage != null) {
			chooser.setStatusText("Rename aborted - " + invalidFilenameMessage);
			cancelListEdit();
			return;
		}

		File editedFileCopy = editedFile;
		int index = model.indexOfFile(editedFileCopy);
		if (index < 0) {
			throw new AssertException("Somehow editing file not in our model.");
		}
		File dest = new File(editedFileCopy.getParentFile(), listEditorField.getText());
		cancelListEdit();
		if (chooser.getModel().renameFile(editedFileCopy, dest)) {
			chooser.setStatusText("");
			model.set(index, dest);
			//chooser.updateFiles(chooser.getCurrentDirectory(), true);
			chooser.setSelectedFileAndUpdateDisplay(dest);
		}
		else {
			chooser.setStatusText("Unable to rename " + editedFileCopy);
		}
	}

	/**
	 * Resizes this list's cell dimensions based on the string widths found in the supplied
	 * list of files.
	 * <p>
	 * If there there are no files, uses the JScrollPane that contains us for the cellwidth.
	 *  
	 * @param files list of files to use to resize the list's fixed cell dimensions.  If null, uses
	 * the model's current set of files.
	 */
	private void recomputeListCellDimensions(List<File> files) {
		files = (files != null) ? files : model.getAllFiles();
		Dimension d =
			cellRenderer.computePlainTextListCellDimensions(this, files, 0, DEFAULT_ICON_SIZE);
		if (d.width == 0 && getParent() != null) {
			// special case: if there were no files to measure, use the containing JScrollPane's
			// width
			if (getParent().getParent() instanceof JScrollPane) {
				JScrollPane parent = (JScrollPane) getParent().getParent();
				Dimension parentSize = parent.getSize();
				Insets insets = parent.getInsets();
				d.width = parentSize.width - (insets != null ? insets.right + insets.left : 0);
			}
		}
		else {
			d.width += DEFAULT_ICON_SIZE + WIDTH_PADDING;
		}
		setFixedCellWidth(d.width);
		setFixedCellHeight(d.height + HEIGHT_PADDING);
	}

	/*junit*/ JTextField getListEditorText() {
		return listEditorField;
	}
}
