import React from "react";
import {mount} from "enzyme";
import PathDisplayComponent from "../../app/browse/PathDisplayComponent";

describe("PathDisplayComponent", ()=>{
    it("should render a horizontal flexbox with an element for each path part", ()=>{
        const rendered = mount(<PathDisplayComponent path="path/to/my/files"/>);

        const pathElems = rendered.find("p.MuiTypography-root");
        expect(pathElems.length).toEqual(4);
        expect(pathElems.at(0).text()).toEqual("path");
        expect(pathElems.at(1).text()).toEqual("to");
        expect(pathElems.at(2).text()).toEqual("my");
        expect(pathElems.at(3).text()).toEqual("files");

        const iconElems = rendered.find("svg.MuiSvgIcon-root");
        expect(iconElems.length).toEqual(4);    //one lead-in icon, then one for each path segment except the last
    });

    it("should not fail if the path is empty", ()=>{
        const rendered = mount(<PathDisplayComponent path=""/>);
        const pathElems = rendered.find("p.MuiTypography-root");
        expect(pathElems.length).toEqual(0);
        const iconElems = rendered.find("svg.MuiSvgIcon-root");
        expect(iconElems.length).toEqual(1);    //one lead-in icon, then one for each path segment except the last
    })
});