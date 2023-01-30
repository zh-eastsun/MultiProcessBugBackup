package com.example.myapplication

import android.app.Activity
import android.view.View

fun <T: View> Activity.fbi(id: Int) = findViewById<T>(id)