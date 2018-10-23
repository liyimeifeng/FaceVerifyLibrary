package com.richbox.merchant.myfacedemo.utils;

import android.graphics.Point;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

public class ParseResult {
    /**
     * 离线人脸框结果解析方法
     *
     * @param json
     * @return
     */
    static public FaceRect[] parseResult(String json) {
        FaceRect[] rect = null;
        if (TextUtils.isEmpty(json)) {
            return null;
        }
        try {
            JSONTokener tokener = new JSONTokener(json);
            JSONObject joResult = new JSONObject(tokener);
            // 获取每个人脸的结果
            JSONArray items = joResult.getJSONArray("face");
            // 获取人脸数目
            rect = new FaceRect[items.length()];
            for (int i = 0; i < items.length(); i++) {

                JSONObject position = items.getJSONObject(i).getJSONObject("position");
                // 提取关键点数据
                rect[i] = new FaceRect();
                rect[i].bound.left = position.getInt("left");
                rect[i].bound.top = position.getInt("top");
                rect[i].bound.right = position.getInt("right");
                rect[i].bound.bottom = position.getInt("bottom");

                try {
                    JSONObject landmark = items.getJSONObject(i).getJSONObject("landmark");
                    int point = 0;
                    String[] keys = new String[]{"right_eye_left_corner", "right_eye_center", "right_eye_right_corner",
                            "left_eye_left_corner", "left_eye_center", "left_eye_right_corner"};
                    rect[i].point = new Point[keys.length];
                    for (int n = 0; n < keys.length; n++) {
                        JSONObject postion = landmark.getJSONObject(keys[n]);
                        rect[i].point[point] = new Point(postion.getInt("x"), postion.getInt("y"));
                        point++;
                    }
                } catch (JSONException e) {
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return rect;
    }
}