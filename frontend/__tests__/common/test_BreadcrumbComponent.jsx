import React from 'react';
import {shallow,mount} from 'enzyme';
import BreadcrumbComponent from '../../app/common/BreadcrumbComponent';
import {BrowserRouter} from 'react-router-dom';

describe("BreadcrumbComponent", ()=> {
    it("should render a path to a set of clickable breadcrumbs", ()=>{
        const rendered = mount(<BrowserRouter root="/"><BreadcrumbComponent path="/path/to/some/page"/></BrowserRouter>);
        console.log(rendered.html());

        const bc = rendered.find("span");
        expect(bc.find("Link").at(0).props().to).toEqual("/path");
        expect(bc.find("Link").at(0).text()).toEqual("path");
        expect(bc.find("Link").at(1).props().to).toEqual("/path/to");
        expect(bc.find("Link").at(1).text()).toEqual("to");
        expect(bc.find("Link").at(2).props().to).toEqual("/path/to/some");
        expect(bc.find("Link").at(2).text()).toEqual("some");
        expect(bc.find("Link").at(3).props().to).toEqual("/path/to/some/page");
        expect(bc.find("Link").at(3).text()).toEqual("page");
        expect(bc.find("Link").length).toEqual(4)
    });
});