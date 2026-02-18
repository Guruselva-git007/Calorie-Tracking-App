package com.calorietracker.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

public class AuthRequestCodeRequest {

    @Email
    @Size(max = 180)
    private String email;

    @Size(max = 30)
    private String phone;

    @Size(max = 120)
    private String name;

    @Size(max = 80)
    private String nickname;

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPhone() {
        return phone;
    }

    public void setPhone(String phone) {
        this.phone = phone;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getNickname() {
        return nickname;
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }
}
