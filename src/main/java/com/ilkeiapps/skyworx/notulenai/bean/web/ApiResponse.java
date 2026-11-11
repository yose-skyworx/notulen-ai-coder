package com.ilkeiapps.skyworx.notulenai.bean.web;

import java.util.ArrayList;
import java.util.List;

import com.ilkeiapps.skyworx.notulenai.bean.ColumnResponse;

import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
public class ApiResponse<T>
{
  private Boolean status;
  private String message;
  private Integer maxPage;
  private Integer perPage;
  private List<T> data;
  private List<ColumnResponse> columns;


  public void insertNewData(T data)
  {
    this.data = new ArrayList<T>();
    this.data.add(data);
  }

  public void addData(T data)
  {
    if (this.data == null) {
      this.data = new ArrayList<T>();
    }
    this.data.add(data);
  }
}
