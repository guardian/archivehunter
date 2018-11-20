import React from 'react';
import {shallow,mount} from 'enzyme';
import FileSizeView from '../../app/Entry/FileSizeView.jsx';

describe("FileSizeView", ()=>{
    it("should correctly display kb",()=>{
        const value = 2*1000;
        const rendered = mount(<FileSizeView rawSize={value}/>);
        expect(rendered.find('span').text()).toEqual("2 Kb");
    })
});