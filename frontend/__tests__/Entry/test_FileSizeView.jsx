import React from 'react';
import {shallow,mount} from 'enzyme';
import FileSizeView from '../../app/Entry/FileSizeView.jsx';

describe("FileSizeView", ()=>{
    it("should correctly display bytes",()=>{
        const value = 2;
        const rendered = mount(<FileSizeView rawSize={value}/>);
        expect(rendered.find('span').text()).toEqual("2 bytes");
    });

    it("should correctly display kb",()=>{
        const value = 2*1000;
        const rendered = mount(<FileSizeView rawSize={value}/>);
        expect(rendered.find('span').text()).toEqual("2 Kb");
    });

    it("should correctly display Mb",()=>{
        const value = 2*1000*1000;
        const rendered = mount(<FileSizeView rawSize={value}/>);
        expect(rendered.find('span').text()).toEqual("2 Mb");
    });

    it("should correctly display Gb",()=>{
        const value = 2*1000*1000*1000;
        const rendered = mount(<FileSizeView rawSize={value}/>);
        expect(rendered.find('span').text()).toEqual("2 Gb");
    });

    it("should correctly display Tb",()=>{
        const value = 2*1000*1000*1000*1000;
        const rendered = mount(<FileSizeView rawSize={value}/>);
        expect(rendered.find('span').text()).toEqual("2 Tb");
    });
});