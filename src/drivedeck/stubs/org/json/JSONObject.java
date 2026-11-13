package org.json;
public class JSONObject {
  public JSONObject(){} public JSONObject(String s) throws JSONException {}
  public String optString(String k){return null;} public String optString(String k,String d){return null;}
  public int optInt(String k,int d){return 0;} public long optLong(String k,long d){return 0;} public boolean optBoolean(String k){return false;}
  public JSONArray optJSONArray(String k){return null;} public JSONObject optJSONObject(String k){return null;}
  public JSONObject put(String k,Object v) throws JSONException {return this;}
}
