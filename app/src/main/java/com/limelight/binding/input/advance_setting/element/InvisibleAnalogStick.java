package com.limelight.binding.input.advance_setting.element;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.InputFilter;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.binding.input.advance_setting.PageDeviceController;
import com.limelight.binding.input.advance_setting.sqlite.SuperConfigDatabaseHelper;
import com.limelight.binding.input.advance_setting.superpage.ElementEditText;
import com.limelight.binding.input.advance_setting.superpage.NumberSeekbar;
import com.limelight.binding.input.advance_setting.superpage.SuperPageLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class InvisibleAnalogStick extends Element {

    /**
     * outer radius size in percent of the ui element
     */
    public static final int SIZE_RADIUS_COMPLETE = 90;
    /**
     * analog stick size in percent of the ui element
     */
    public static final int SIZE_RADIUS_ANALOG_STICK = 90;
    /**
     * dead zone size in percent of the ui element
     */
    public static final int SIZE_RADIUS_DEADZONE = 90;
    /**
     * time frame for a double click
     */
    public final static long timeoutDoubleClick = 350;

    /**
     * touch down time until the deadzone is lifted to allow precise movements with the analog sticks
     */
    public final static long timeoutDeadzone = 150;

    /**
     * Listener interface to update registered observers.
     */
    public interface InvisibleAnalogStickListener {

        /**
         * onMovement event will be fired on real analog stick movement (outside of the deadzone).
         *
         * @param x horizontal position, value from -1.0 ... 0 .. 1.0
         * @param y vertical position, value from -1.0 ... 0 .. 1.0
         */
        void onMovement(float x, float y);

        /**
         * onClick event will be fired on click on the analog stick
         */
        void onClick();

        /**
         * onDoubleClick event will be fired on a double click in a short time frame on the analog
         * stick.
         */
        void onDoubleClick();

        /**
         * onRevoke event will be fired on unpress of the analog stick.
         */
        void onRevoke();
    }

    /**
     * Movement states of the analog sick.
     */
    private enum STICK_STATE {
        NO_MOVEMENT,
        MOVED_IN_DEAD_ZONE,
        MOVED_ACTIVE
    }

    /**
     * Click type states.
     */
    private enum CLICK_STATE {
        SINGLE,
        DOUBLE
    }

    /**
     * configuration if the analog stick should be displayed as circle or square
     */
    private boolean circle_stick = true; // TODO: implement square sick for simulations

    /**
     * outer radius, this size will be automatically updated on resize
     */
    private float radius_complete = 0;
    /**
     * analog stick radius, this size will be automatically updated on resize
     */
    private float radius_analog_stick = 0;
    /**
     * dead zone radius, this size will be automatically updated on resize
     */
    private float radius_dead_zone = 0;

    /**
     * horizontal position in relation to the center of the element
     */
    private float relative_x = 0;
    /**
     * vertical position in relation to the center of the element
     */
    private float relative_y = 0;


    private double movement_radius = 0;
    private double movement_angle = 0;

    private float position_stick_x = 0;
    private float position_stick_y = 0;
    private float circleCenterX = 0;
    private float circleCenterY = 0;

    private SuperConfigDatabaseHelper superConfigDatabaseHelper;
    private PageDeviceController pageDeviceController;
    private InvisibleAnalogStick invisibleAnalogStick;

    private ElementController.SendEventHandler middleValueSendHandler;
    private ElementController.SendEventHandler valueSendHandler;
    private String middleValue;
    private String value;
    private int radius;
    private int sense; //dead zone radius
    private int layer;
    private int thick;
    private int normalColor;
    private int pressedColor;
    private int backgroundColor;

    private SuperPageLayout invisibleAnalogStickPage;
    private NumberSeekbar centralXNumberSeekbar;
    private NumberSeekbar centralYNumberSeekbar;

    private final Paint paintStick = new Paint();
    private final Paint paintBackground = new Paint();
    private final Paint paintEdit = new Paint();
    private final RectF rect = new RectF();

    private InvisibleAnalogStick.STICK_STATE stick_state = InvisibleAnalogStick.STICK_STATE.NO_MOVEMENT;
    private InvisibleAnalogStick.CLICK_STATE click_state = InvisibleAnalogStick.CLICK_STATE.SINGLE;

    private InvisibleAnalogStickListener listener;
    private List<InvisibleAnalogStickListener> listeners = new ArrayList<>();
    private long timeLastClick = 0;

    private static double getMovementRadius(float x, float y) {
        return Math.sqrt(x * x + y * y);
    }

    private static double getAngle(float way_x, float way_y) {
        // prevent divisions by zero for corner cases
        if (way_x == 0) {
            return way_y < 0 ? Math.PI : 0;
        } else if (way_y == 0) {
            if (way_x > 0) {
                return Math.PI * 3 / 2;
            } else if (way_x < 0) {
                return Math.PI * 1 / 2;
            }
        }
        // return correct calculated angle for each quadrant
        if (way_x > 0) {
            if (way_y < 0) {
                // first quadrant
                return 3 * Math.PI / 2 + Math.atan((double) (-way_y / way_x));
            } else {
                // second quadrant
                return Math.PI + Math.atan((double) (way_x / way_y));
            }
        } else {
            if (way_y > 0) {
                // third quadrant
                return Math.PI / 2 + Math.atan((double) (way_y / -way_x));
            } else {
                // fourth quadrant
                return 0 + Math.atan((double) (-way_x / -way_y));
            }
        }
    }

    public InvisibleAnalogStick(Map<String,Object> attributesMap,
                                ElementController controller,
                                PageDeviceController pageDeviceController, Context context) {
        super(attributesMap,controller,context);
        // reset stick position
        circleCenterX = getWidth() / 2;
        circleCenterY = getHeight() / 2;
        position_stick_x = circleCenterX;
        position_stick_y = circleCenterY;


        this.superConfigDatabaseHelper = controller.getSuperConfigDatabaseHelper();
        this.pageDeviceController = pageDeviceController;
        this.invisibleAnalogStick = this;


        DisplayMetrics displayMetrics = context.getResources().getDisplayMetrics();
        super.centralXMax  = displayMetrics.widthPixels;
        super.centralXMin  = 0;
        super.centralYMax  = displayMetrics.heightPixels;
        super.centralYMin  = 0;
        super.widthMax  = displayMetrics.widthPixels;
        super.widthMin  = 100;
        super.heightMax  = displayMetrics.heightPixels;
        super.heightMin  = 100;

        paintBackground.setStyle(Paint.Style.FILL);
        paintStick.setStyle(Paint.Style.STROKE);
        paintEdit.setStyle(Paint.Style.STROKE);
        paintEdit.setStrokeWidth(4);
        paintEdit.setPathEffect(new DashPathEffect(new float[]{10, 20}, 0));

        radius = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_RADIUS)).intValue();
        sense = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_SENSE)).intValue();
        layer = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_LAYER)).intValue();
        thick = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_THICK)).intValue();
        normalColor = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_NORMAL_COLOR)).intValue();
        pressedColor = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_PRESSED_COLOR)).intValue();
        backgroundColor = ((Long) attributesMap.get(COLUMN_INT_ELEMENT_BACKGROUND_COLOR)).intValue();
        value = (String) attributesMap.get(COLUMN_STRING_ELEMENT_VALUE);
        middleValue = (String) attributesMap.get(COLUMN_STRING_ELEMENT_MIDDLE_VALUE);
        valueSendHandler = controller.getSendEventHandler(value);
        middleValueSendHandler = controller.getSendEventHandler(middleValue);

        listener = new InvisibleAnalogStickListener() {
            @Override
            public void onMovement(float x, float y) {
                valueSendHandler.sendEvent((int) (x * 0x7FFE),(int) (y * 0x7FFE));
            }

            @Override
            public void onClick() {
            }

            @Override
            public void onDoubleClick() {
                middleValueSendHandler.sendEvent(true);
            }

            @Override
            public void onRevoke() {
                middleValueSendHandler.sendEvent(false);
            }
        };

        radius_complete = getPercent(radius, 100) - 2 * thick;
        radius_dead_zone = getPercent(radius, sense);
        radius_analog_stick = getPercent(radius, 20);
    }

    private void notifyOnMovement(float x, float y) {
        // notify listeners
        listener.onMovement(x, y);
    }

    private void notifyOnClick() {
        // notify listeners
        listener.onClick();
    }

    private void notifyOnDoubleClick() {
        // notify listeners
        listener.onDoubleClick();
    }

    private void notifyOnRevoke() {
        // notify listeners
        listener.onRevoke();
    }

    @Override
    protected SuperPageLayout getInfoPage() {
        if (invisibleAnalogStickPage == null){
            invisibleAnalogStickPage = (SuperPageLayout) LayoutInflater.from(getContext()).inflate(R.layout.page_invisible_analog_stick,null);
            centralXNumberSeekbar = invisibleAnalogStickPage.findViewById(R.id.page_invisible_analog_stick_central_x);
            centralYNumberSeekbar = invisibleAnalogStickPage.findViewById(R.id.page_invisible_analog_stick_central_y);

        }

        NumberSeekbar widthNumberSeekbar = invisibleAnalogStickPage.findViewById(R.id.page_invisible_analog_stick_width);
        NumberSeekbar heightNumberSeekbar = invisibleAnalogStickPage.findViewById(R.id.page_invisible_analog_stick_height);
        NumberSeekbar radiusNumberSeekbar = invisibleAnalogStickPage.findViewById(R.id.page_invisible_analog_stick_radius);
        RadioGroup valueRadioGroup = invisibleAnalogStickPage.findViewById(R.id.page_invisible_analog_stick_value);
        TextView middleValueTextView = invisibleAnalogStickPage.findViewById(R.id.page_invisible_analog_stick_middle_value);
        NumberSeekbar senseNumberSeekbar = invisibleAnalogStickPage.findViewById(R.id.page_invisible_analog_stick_sense);
        NumberSeekbar thickNumberSeekbar = invisibleAnalogStickPage.findViewById(R.id.page_invisible_analog_stick_thick);
        ElementEditText normalColorElementEditText = invisibleAnalogStickPage.findViewById(R.id.page_invisible_analog_stick_normal_color);
        ElementEditText pressedColorElementEditText = invisibleAnalogStickPage.findViewById(R.id.page_invisible_analog_stick_pressed_color);
        ElementEditText backgroundColorElementEditText = invisibleAnalogStickPage.findViewById(R.id.page_invisible_analog_stick_background_color);
        Button copyButton = invisibleAnalogStickPage.findViewById(R.id.page_invisible_analog_stick_copy);
        Button deleteButton = invisibleAnalogStickPage.findViewById(R.id.page_invisible_analog_stick_delete);


        RadioButton radioButton = valueRadioGroup.findViewWithTag(value);
        radioButton.setChecked(true);
        valueRadioGroup.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                value = group.findViewById(checkedId).getTag().toString();
                ContentValues contentValues = new ContentValues();
                contentValues.put(COLUMN_STRING_ELEMENT_VALUE, value);
                superConfigDatabaseHelper.updateElement(elementId,contentValues);

                valueSendHandler = elementController.getSendEventHandler(value);
            }
        });

        middleValueTextView.setText(pageDeviceController.getKeyNameByValue(middleValue));
        middleValueTextView.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                PageDeviceController.DeviceCallBack deviceCallBack = new PageDeviceController.DeviceCallBack() {
                    @Override
                    public void OnKeyClick(TextView key) {
                        middleValue = key.getTag().toString();
                        // page页设置值文本
                        ((TextView) v).setText(key.getText());
                        // 保存值
                        ContentValues contentValues = new ContentValues();
                        contentValues.put(COLUMN_STRING_ELEMENT_MIDDLE_VALUE, middleValue);
                        superConfigDatabaseHelper.updateElement(elementId,contentValues);
                        // 设置onClickListener
                        middleValueSendHandler = elementController.getSendEventHandler(middleValue);
                    }
                };
                pageDeviceController.open(deviceCallBack,View.VISIBLE,View.VISIBLE,View.VISIBLE);
            }
        });

        centralXNumberSeekbar.setProgressMin(centralXMin);
        centralXNumberSeekbar.setProgressMax(centralXMax);
        centralXNumberSeekbar.setValueWithNoCallBack(getParamCentralX());
        centralXNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(int progress) {
                setParamCentralX(progress);
            }

            @Override
            public void onProgressRelease(int lastProgress) {
                ContentValues contentValues = new ContentValues();
                contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_X,getParamCentralX());
                superConfigDatabaseHelper.updateElement(elementId,contentValues);
            }
        });

        centralYNumberSeekbar.setProgressMin(centralYMin);
        centralYNumberSeekbar.setProgressMax(centralYMax);
        centralYNumberSeekbar.setValueWithNoCallBack(getParamCentralY());
        centralYNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(int progress) {
                setParamCentralY(progress);
            }

            @Override
            public void onProgressRelease(int lastProgress) {
                ContentValues contentValues = new ContentValues();
                contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_Y,getParamCentralY());
                superConfigDatabaseHelper.updateElement(elementId,contentValues);
            }
        });


        widthNumberSeekbar.setProgressMax(widthMax);
        widthNumberSeekbar.setProgressMin(widthMin);
        widthNumberSeekbar.setValueWithNoCallBack(getParamWidth());
        widthNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(int progress) {
                setParamWidth(progress);
            }

            @Override
            public void onProgressRelease(int lastProgress) {
                radiusNumberSeekbar.setProgressMax(Math.min(getParamWidth(),getParamHeight()) / 2);
                ContentValues contentValues = new ContentValues();
                contentValues.put(COLUMN_INT_ELEMENT_WIDTH,getParamWidth());
                superConfigDatabaseHelper.updateElement(elementId,contentValues);

            }
        });

        heightNumberSeekbar.setProgressMax(heightMax);
        heightNumberSeekbar.setProgressMin(heightMin);
        heightNumberSeekbar.setValueWithNoCallBack(getParamHeight());
        heightNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(int progress) {
                setParamHeight(progress);
            }

            @Override
            public void onProgressRelease(int lastProgress) {
                radiusNumberSeekbar.setProgressMax(Math.min(getParamWidth(),getParamHeight()) / 2);
                ContentValues contentValues = new ContentValues();
                contentValues.put(COLUMN_INT_ELEMENT_HEIGHT,getParamHeight());
                superConfigDatabaseHelper.updateElement(elementId,contentValues);
            }
        });


        senseNumberSeekbar.setValueWithNoCallBack(sense);
        senseNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(int progress) {
                sense = progress;
                radius_dead_zone = getPercent(radius, sense);
                invisibleAnalogStick.invalidate();
            }

            @Override
            public void onProgressRelease(int lastProgress) {
                ContentValues contentValues = new ContentValues();
                contentValues.put(COLUMN_INT_ELEMENT_SENSE,sense);
                superConfigDatabaseHelper.updateElement(elementId,contentValues);
            }
        });





        radiusNumberSeekbar.setProgressMax(Math.min(getParamWidth(),getParamHeight()) / 2);
        radiusNumberSeekbar.setProgressMin(10);
        radiusNumberSeekbar.setValueWithNoCallBack(radius);
        radiusNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(int progress) {
                radius = progress;
                radius_complete = getPercent(radius, 100) - 2 * thick;
                radius_dead_zone = getPercent(radius, sense);
                radius_analog_stick = getPercent(radius, 20);
                invisibleAnalogStick.invalidate();
            }

            @Override
            public void onProgressRelease(int lastProgress) {
                ContentValues contentValues = new ContentValues();
                contentValues.put(COLUMN_INT_ELEMENT_RADIUS,radius);
                superConfigDatabaseHelper.updateElement(elementId,contentValues);
            }
        });


        thickNumberSeekbar.setValueWithNoCallBack(thick);
        thickNumberSeekbar.setOnNumberSeekbarChangeListener(new NumberSeekbar.OnNumberSeekbarChangeListener() {
            @Override
            public void onProgressChanged(int progress) {
                thick = progress;
                invisibleAnalogStick.invalidate();
            }

            @Override
            public void onProgressRelease(int lastProgress) {
                ContentValues contentValues = new ContentValues();
                contentValues.put(COLUMN_INT_ELEMENT_THICK,thick);
                superConfigDatabaseHelper.updateElement(elementId,contentValues);
            }
        });


        normalColorElementEditText.setTextWithNoTextChangedCallBack(String.format("%08X",normalColor));
        normalColorElementEditText.setFilters(new InputFilter[]{new InputFilter.AllCaps(), new Element.HexInputFilter()});
        normalColorElementEditText.setOnTextChangedListener(new ElementEditText.OnTextChangedListener() {
            @Override
            public void textChanged(String text) {
                if (text.matches("^[A-F0-9]{8}$")){
                    normalColor = (int) Long.parseLong(text, 16);
                    invisibleAnalogStick.invalidate();
                    ContentValues contentValues = new ContentValues();
                    contentValues.put(COLUMN_INT_ELEMENT_NORMAL_COLOR,normalColor);
                    superConfigDatabaseHelper.updateElement(elementId,contentValues);
                }
            }
        });


        pressedColorElementEditText.setTextWithNoTextChangedCallBack(String.format("%08X",pressedColor));
        pressedColorElementEditText.setFilters(new InputFilter[]{new InputFilter.AllCaps(), new Element.HexInputFilter()});
        pressedColorElementEditText.setOnTextChangedListener(new ElementEditText.OnTextChangedListener() {
            @Override
            public void textChanged(String text) {
                if (text.matches("^[A-F0-9]{8}$")){
                    pressedColor = (int) Long.parseLong(text, 16);
                    invisibleAnalogStick.invalidate();
                    ContentValues contentValues = new ContentValues();
                    contentValues.put(COLUMN_INT_ELEMENT_PRESSED_COLOR,pressedColor);
                    superConfigDatabaseHelper.updateElement(elementId,contentValues);
                }
            }
        });


        backgroundColorElementEditText.setTextWithNoTextChangedCallBack(String.format("%08X",backgroundColor));
        backgroundColorElementEditText.setFilters(new InputFilter[]{new InputFilter.AllCaps(), new Element.HexInputFilter()});
        backgroundColorElementEditText.setOnTextChangedListener(new ElementEditText.OnTextChangedListener() {
            @Override
            public void textChanged(String text) {
                if (text.matches("^[A-F0-9]{8}$")){
                    backgroundColor = (int) Long.parseLong(text, 16);
                    invisibleAnalogStick.invalidate();
                    ContentValues contentValues = new ContentValues();
                    contentValues.put(COLUMN_INT_ELEMENT_BACKGROUND_COLOR,backgroundColor);
                    superConfigDatabaseHelper.updateElement(elementId,contentValues);
                }
            }
        });


        copyButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                ContentValues contentValues = new ContentValues();
                contentValues.put(COLUMN_INT_ELEMENT_TYPE,ELEMENT_TYPE_INVISIBLE_ANALOG_STICK);
                contentValues.put(COLUMN_STRING_ELEMENT_VALUE, value);
                contentValues.put(COLUMN_STRING_ELEMENT_MIDDLE_VALUE, middleValue);
                contentValues.put(COLUMN_INT_ELEMENT_SENSE,sense);
                contentValues.put(COLUMN_INT_ELEMENT_WIDTH,getParamWidth());
                contentValues.put(COLUMN_INT_ELEMENT_HEIGHT,getParamHeight());
                contentValues.put(COLUMN_INT_ELEMENT_LAYER,layer);
                contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_X,Math.max(Math.min(getParamCentralX() + getParamWidth(),centralXMax),centralXMin));
                contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_Y,getParamCentralY());
                contentValues.put(COLUMN_INT_ELEMENT_RADIUS,radius);
                contentValues.put(COLUMN_INT_ELEMENT_THICK,thick);
                contentValues.put(COLUMN_INT_ELEMENT_NORMAL_COLOR,normalColor);
                contentValues.put(COLUMN_INT_ELEMENT_PRESSED_COLOR,pressedColor);
                contentValues.put(COLUMN_INT_ELEMENT_BACKGROUND_COLOR,backgroundColor);
                elementController.addElement(contentValues);
            }
        });

        deleteButton.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                elementController.toggleInfoPage(invisibleAnalogStickPage);
                elementController.deleteElement(invisibleAnalogStick);
            }
        });



        return invisibleAnalogStickPage;
    }

    @Override
    public void updateDataBase() {
        ContentValues contentValues = new ContentValues();
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_X,getParamCentralX());
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_Y,getParamCentralY());
        superConfigDatabaseHelper.updateElement(elementId,contentValues);

    }

    @Override
    protected void updatePageInfo() {
        if (invisibleAnalogStickPage != null){
            centralXNumberSeekbar.setValueWithNoCallBack(getParamCentralX());
            centralYNumberSeekbar.setValueWithNoCallBack(getParamCentralY());
        }

    }

    @Override
    protected void onElementDraw(Canvas canvas) {
        // set transparent background
        paintBackground.setColor(backgroundColor);
        rect.top = 0;
        rect.left = 0;
        rect.right = getWidth();
        rect.bottom = getHeight();
        canvas.drawRect(rect,paintBackground);

        if (elementController.getMode() == ElementController.Mode.Edit){
            // 绘画范围
            rect.left = rect.top = 2;
            rect.right = getWidth() - 2;
            rect.bottom = getHeight() - 2;
            // 边框
            paintEdit.setColor(editColor);
            canvas.drawRect(rect,paintEdit);


            paintStick.setStrokeWidth(thick);
            // draw outer circle
            if (!isPressed() || click_state == InvisibleAnalogStick.CLICK_STATE.SINGLE) {
                paintStick.setColor(normalColor);
            } else {
                paintStick.setColor(pressedColor);
            }
            canvas.drawCircle(getWidth() / 2, getHeight() / 2 , radius_complete, paintStick);

            paintStick.setColor(normalColor);
            // draw dead zone
            canvas.drawCircle(getWidth() / 2, getHeight() / 2, radius_dead_zone, paintStick);

            paintStick.setColor(normalColor);
            canvas.drawCircle(getWidth() / 2, getHeight() / 2, radius_analog_stick, paintStick);
        }

        if (!isPressed()){
            return;
        }


        paintStick.setStyle(Paint.Style.STROKE);
        paintStick.setStrokeWidth(thick);
        // draw outer circle
        if (!isPressed() || click_state == InvisibleAnalogStick.CLICK_STATE.SINGLE) {
            paintStick.setColor(normalColor);
        } else {
            paintStick.setColor(pressedColor);
        }
        canvas.drawCircle(circleCenterX, circleCenterY, radius_complete, paintStick);

        paintStick.setColor(normalColor);
        // draw dead zone
        canvas.drawCircle(circleCenterX, circleCenterY, radius_dead_zone, paintStick);

        // draw stick depending on state
        switch (stick_state) {
            case NO_MOVEMENT: {
                paintStick.setColor(normalColor);
                canvas.drawCircle(circleCenterX, circleCenterY, radius_analog_stick, paintStick);
                break;
            }
            case MOVED_IN_DEAD_ZONE:
            case MOVED_ACTIVE: {
                paintStick.setColor(pressedColor);
                canvas.drawCircle(position_stick_x, position_stick_y, radius_analog_stick, paintStick);
                break;
            }
        }
    }

    private void updatePosition(long eventTime) {
        // get 100% way
        float complete = radius_complete - radius_analog_stick;

        // calculate relative way
        float correlated_y = (float) (Math.sin(Math.PI / 2 - movement_angle) * (movement_radius));
        float correlated_x = (float) (Math.cos(Math.PI / 2 - movement_angle) * (movement_radius));

        // update positions
        position_stick_x = circleCenterX - correlated_x;
        position_stick_y = circleCenterY - correlated_y;

        // Stay active even if we're back in the deadzone because we know the user is actively
        // giving analog stick input and we don't want to snap back into the deadzone.
        // We also release the deadzone if the user keeps the stick pressed for a bit to allow
        // them to make precise movements.
        stick_state = (stick_state == InvisibleAnalogStick.STICK_STATE.MOVED_ACTIVE ||
                eventTime - timeLastClick > timeoutDeadzone ||
                movement_radius > radius_dead_zone) ?
                InvisibleAnalogStick.STICK_STATE.MOVED_ACTIVE : InvisibleAnalogStick.STICK_STATE.MOVED_IN_DEAD_ZONE;

        //  trigger move event if state active
        if (stick_state == InvisibleAnalogStick.STICK_STATE.MOVED_ACTIVE) {
            notifyOnMovement(-correlated_x / complete, correlated_y / complete);
        }
    }

    @Override
    public boolean onElementTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN){
            //点击后扩大view，防止摇杆显示不全
            circleCenterX = event.getX();
            circleCenterY = event.getY();
            invalidate();
        }
        // save last click state
        InvisibleAnalogStick.CLICK_STATE lastClickState = click_state;

        // get absolute way for each axis
        relative_x = -(circleCenterX - event.getX());
        relative_y = -(circleCenterY - event.getY());

        // get radius and angel of movement from center
        movement_radius = getMovementRadius(relative_x, relative_y);
        movement_angle = getAngle(relative_x, relative_y);

        // pass touch event to parent if out of outer circle
        if (movement_radius > radius_complete && !isPressed())
            return false;

        // chop radius if out of outer circle or near the edge
        if (movement_radius > (radius_complete - radius_analog_stick)) {
            movement_radius = radius_complete - radius_analog_stick;
        }

        // handle event depending on action
        switch (event.getActionMasked()) {
            // down event (touch event)
            case MotionEvent.ACTION_DOWN: {

                // set to dead zoned, will be corrected in update position if necessary
                stick_state = InvisibleAnalogStick.STICK_STATE.MOVED_IN_DEAD_ZONE;
                // check for double click
                if (lastClickState == InvisibleAnalogStick.CLICK_STATE.SINGLE &&
                        event.getEventTime() - timeLastClick <= timeoutDoubleClick) {
                    click_state = InvisibleAnalogStick.CLICK_STATE.DOUBLE;
                    notifyOnDoubleClick();
                } else {
                    click_state = InvisibleAnalogStick.CLICK_STATE.SINGLE;
                    notifyOnClick();
                }
                // reset last click timestamp
                timeLastClick = event.getEventTime();
                // set item pressed and update
                setPressed(true);
                break;
            }
            // up event (revoke touch)
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP: {
                setPressed(false);
                break;
            }
        }

        if (isPressed()) {
            // when is pressed calculate new positions (will trigger movement if necessary)
            updatePosition(event.getEventTime());
        } else {
            stick_state = InvisibleAnalogStick.STICK_STATE.NO_MOVEMENT;
            notifyOnRevoke();

            // not longer pressed reset analog stick
            notifyOnMovement(0, 0);
        }
        // refresh view
        invalidate();
        // accept the touch event
        return true;
    }

    public static ContentValues getInitialInfo(){
        ContentValues contentValues = new ContentValues();
        contentValues.put(COLUMN_INT_ELEMENT_TYPE,ELEMENT_TYPE_INVISIBLE_ANALOG_STICK);
        contentValues.put(COLUMN_STRING_ELEMENT_VALUE,"LS");
        contentValues.put(COLUMN_STRING_ELEMENT_MIDDLE_VALUE,"g64");
        contentValues.put(COLUMN_INT_ELEMENT_SENSE,30);
        contentValues.put(COLUMN_INT_ELEMENT_WIDTH,400);
        contentValues.put(COLUMN_INT_ELEMENT_HEIGHT,400);
        contentValues.put(COLUMN_INT_ELEMENT_LAYER,45);
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_X,400);
        contentValues.put(COLUMN_INT_ELEMENT_CENTRAL_Y,400);
        contentValues.put(COLUMN_INT_ELEMENT_RADIUS,100);
        contentValues.put(COLUMN_INT_ELEMENT_THICK,5);
        contentValues.put(COLUMN_INT_ELEMENT_NORMAL_COLOR,0xF0888888);
        contentValues.put(COLUMN_INT_ELEMENT_PRESSED_COLOR,0xF00000FF);
        contentValues.put(COLUMN_INT_ELEMENT_BACKGROUND_COLOR,0x00FFFFFF);
        return contentValues;


    }
}
