package com.android.systemui.qs;

import static com.android.systemui.util.Utils.useQsMediaPlayer;

import android.content.Context;
import android.content.ContentResolver;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import com.android.systemui.R;
import com.android.systemui.qs.QSPanel.QSTileLayout;
import com.android.systemui.qs.QSPanel.TileRecord;

import java.util.ArrayList;

public class TileLayout extends ViewGroup implements QSTileLayout {

    public static final int NO_MAX_COLUMNS = 100;
    private static final float TILE_ASPECT = 1.2f;

    private static final String TAG = "TileLayout";

    protected int mColumns;
    protected int mCellWidth;
    protected int mCellHeight;
    protected int mCellMarginHorizontal;
    protected int mCellMarginVertical;
    protected int mSidePadding;
    protected int mRows = 1;
    protected int mDefaultColumns;
    protected boolean mShowTitles = true;
    private boolean mLayoutChanged = false;

    protected final ArrayList<TileRecord> mRecords = new ArrayList<>();
    private int mCellMarginTop;
    protected boolean mListening;
    protected int mMaxAllowedRows = 3;

    // Prototyping with less rows
    private final boolean mLessRows;
    private int mMinRows = 2;
    private int mMaxColumns = NO_MAX_COLUMNS;
    private int mResourceColumns;
    private boolean mHorizontalWithQSMediaPlayer = false;

    public TileLayout(Context context) {
        this(context, null);
    }

    public TileLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        setFocusableInTouchMode(true);
        mLessRows = (Settings.System.getInt(context.getContentResolver(), "qs_less_rows", 0) != 0);
        updateResources();

    }

    @Override
    public int getOffsetTop(TileRecord tile) {
        return getTop();
    }

    @Override
    public void setListening(boolean listening) {
        if (mListening == listening) return;
        mListening = listening;
        for (TileRecord record : mRecords) {
            record.tile.setListening(this, mListening);
        }
    }

    @Override
    public boolean setMinRows(int minRows) {
        if (mMinRows != minRows) {
            mMinRows = minRows;
            updateResources();
            return true;
        }
        return false;
    }

    @Override
    public boolean setMaxColumns(int maxColumns) {
        mMaxColumns = maxColumns;
        return updateColumns();
    }

    public void addTile(TileRecord tile) {
        mRecords.add(tile);
        tile.tile.setListening(this, mListening);
        addTileView(tile);
    }

    protected void addTileView(TileRecord tile) {
        addView(tile.tileView);
    }

    @Override
    public void removeTile(TileRecord tile) {
        mRecords.remove(tile);
        tile.tile.setListening(this, false);
        removeView(tile.tileView);
    }

    public void removeAllViews() {
        for (TileRecord record : mRecords) {
            record.tile.setListening(this, false);
        }
        mRecords.clear();
        super.removeAllViews();
    }

    public boolean updateResources() {
        final Resources res = mContext.getResources();
        mCellHeight = mContext.getResources().getDimensionPixelSize(R.dimen.qs_tile_height);
        mCellMarginHorizontal = res.getDimensionPixelSize(R.dimen.qs_tile_margin_horizontal);
        mCellMarginVertical= res.getDimensionPixelSize(R.dimen.qs_tile_margin_vertical);
        mCellMarginTop = res.getDimensionPixelSize(R.dimen.qs_tile_margin_top);
        mMaxAllowedRows = Math.max(1, getResources().getInteger(R.integer.quick_settings_max_rows));
        if (mLessRows) mMaxAllowedRows = Math.max(mMinRows, mMaxAllowedRows - 1);
        updateSettings();
        return mLayoutChanged;
    }

    private boolean updateColumns() {
        updateSettings();
        int oldColumns = mColumns;
        mColumns = Math.min(mColumns, mMaxColumns);
        return oldColumns != mColumns;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // If called with AT_MOST, it will limit the number of rows. If called with UNSPECIFIED
        // it will show all its tiles. In this case, the tiles have to be entered before the
        // container is measured. Any change in the tiles, should trigger a remeasure.
        final int numTiles = mRecords.size();
        final int width = MeasureSpec.getSize(widthMeasureSpec);
        final int availableWidth = width - getPaddingStart() - getPaddingEnd();
        final int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        if (heightMode == MeasureSpec.UNSPECIFIED) {
            mRows = (numTiles + mColumns - 1) / mColumns;
        }
        mCellWidth =
                (availableWidth - (mCellMarginHorizontal * mColumns)) / mColumns;

        // Measure each QS tile.
        View previousView = this;
        for (TileRecord record : mRecords) {
            if (record.tileView.getVisibility() == GONE) continue;
            record.tileView.measure(exactly(mCellWidth), exactly(mCellHeight));
            previousView = record.tileView.updateAccessibilityOrder(previousView);
        }

        // Only include the top margin in our measurement if we have more than 1 row to show.
        // Otherwise, don't add the extra margin buffer at top.
        int height = (mCellHeight + mCellMarginVertical) * mRows +
                (mRows != 0 ? (mCellMarginTop - mCellMarginVertical) : 0);
        if (height < 0) height = 0;

        setMeasuredDimension(width, height);
    }

    /**
     * Determines the maximum number of rows that can be shown based on height. Clips at a minimum
     * of 1 and a maximum of mMaxAllowedRows.
     *
     * @param allowedHeight The height this view has visually available
     * @param tilesCount Upper limit on the number of tiles to show. to prevent empty rows.
     */
    public boolean updateMaxRows(int allowedHeight, int tilesCount) {
        final Resources res = getContext().getResources();
        final ContentResolver resolver = mContext.getContentResolver();
        final int availableHeight =  allowedHeight - mCellMarginTop
                // Add the cell margin in order to divide easily by the height + the margin below
                + mCellMarginVertical;
        final int previousRows = mRows;
        if (res.getConfiguration().orientation == Configuration.ORIENTATION_PORTRAIT) {
            mRows = Settings.System.getIntForUser(resolver,
                    Settings.System.OMNI_QS_LAYOUT_ROWS, 3,
                    UserHandle.USER_CURRENT);
        } else {
            mRows = Settings.System.getIntForUser(resolver,
                    Settings.System.OMNI_QS_LAYOUT_ROWS_LANDSCAPE, 2,
                    UserHandle.USER_CURRENT);
        }
        if (mRows < mMinRows) {
            mRows = mMinRows;
        }
        if (mRows > (tilesCount + mColumns - 1) / mColumns) {
            mRows = (tilesCount + mColumns - 1) / mColumns;
        }
        return previousRows != mRows;
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    protected static int exactly(int size) {
        return MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY);
    }


    protected void layoutTileRecords(int numRecords) {
        final boolean isRtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        int row = 0;
        int column = 0;

        // Layout each QS tile.
        final int tilesToLayout = Math.min(numRecords, mRows * mColumns);
        for (int i = 0; i < tilesToLayout; i++, column++) {
            // If we reached the last column available to layout a tile, wrap back to the next row.
            if (column == mColumns) {
                column = 0;
                row++;
            }

            final TileRecord record = mRecords.get(i);
            final int top = getRowTop(row);
            final int left = getColumnStart(isRtl ? mColumns - column - 1 : column);
            final int right = left + mCellWidth;
            record.tileView.layout(left, top, right, top + record.tileView.getMeasuredHeight());
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        layoutTileRecords(mRecords.size());
    }

    private int getRowTop(int row) {
        return row * (mCellHeight + mCellMarginVertical) + mCellMarginTop;
    }

    protected int getColumnStart(int column) {
        return getPaddingStart() + mCellMarginHorizontal / 2 +
                column *  (mCellWidth + mCellMarginHorizontal);
    }

    @Override
    public int getNumColumns() {
        return mColumns;
    }

    @Override
    public int getNumRows() {
        return mRows;
    }

    @Override
    public int getNumVisibleTiles() {
        return mRecords.size();
    }

    public void updateSettings() {
        final Resources res = mContext.getResources();
        mDefaultColumns = Math.max(1, res.getInteger(R.integer.quick_settings_num_columns));
        int defaultRows = Math.max(1, res.getInteger(R.integer.quick_settings_num_columns));
        int defaultRowsLandscape = Math.min(2, res.getInteger(R.integer.quick_settings_max_rows));
        boolean isPortrait = res.getConfiguration().orientation
                == Configuration.ORIENTATION_PORTRAIT;
        int columns = Settings.System.getIntForUser(
                mContext.getContentResolver(), Settings.System.OMNI_QS_LAYOUT_COLUMNS, mDefaultColumns,
                UserHandle.USER_CURRENT);
        int columnsLandscape = Settings.System.getIntForUser(
                mContext.getContentResolver(), Settings.System.OMNI_QS_LAYOUT_COLUMNS_LANDSCAPE, mDefaultColumns,
                UserHandle.USER_CURRENT);
        int rows = Settings.System.getIntForUser(
                mContext.getContentResolver(), Settings.System.OMNI_QS_LAYOUT_ROWS, defaultRows,
                UserHandle.USER_CURRENT);
        int rowsLandscape = Settings.System.getIntForUser(
                mContext.getContentResolver(), Settings.System.OMNI_QS_LAYOUT_ROWS_LANDSCAPE, defaultRowsLandscape,
                UserHandle.USER_CURRENT);
        boolean showTitles = Settings.System.getIntForUser(
                mContext.getContentResolver(), Settings.System.OMNI_QS_TILE_TITLE_VISIBILITY, 1,
                UserHandle.USER_CURRENT) == 1;
        if (showTitles) {
            mCellHeight = mContext.getResources().getDimensionPixelSize(R.dimen.qs_tile_height);
        } else {
            mCellHeight = mContext.getResources().getDimensionPixelSize(R.dimen.qs_tile_height_wo_label);
        }
        if (mColumns != (isPortrait ? columns : mHorizontalWithQSMediaPlayer ? 3 : columnsLandscape)
                || mShowTitles != showTitles) {
            mColumns = isPortrait ? columns : mHorizontalWithQSMediaPlayer ? 3 : columnsLandscape;
            mShowTitles = showTitles;
            mLayoutChanged = true;
            requestLayout();
        }
        if (mRows != (isPortrait ? rows : mHorizontalWithQSMediaPlayer ? 2 : rowsLandscape)
                || mShowTitles != showTitles) {
            mRows = isPortrait ? rows : mHorizontalWithQSMediaPlayer ? 2 : rowsLandscape;
            mShowTitles = showTitles;
            mLayoutChanged = true;
            requestLayout();
        }
    }

    @Override
    public boolean isShowTitles() {
        return mShowTitles;
    }

    public void setHorizontalWithQsMediaPlayer(boolean horizontalWithQSMediaPlayer) {
        mHorizontalWithQSMediaPlayer = horizontalWithQSMediaPlayer;
        updateSettings();
    }
}
