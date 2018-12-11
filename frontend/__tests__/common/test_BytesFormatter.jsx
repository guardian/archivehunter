import React from 'react';
import {shallow,mount} from 'enzyme';
import BytesFormatter from '../../app/common/BytesFormatter.jsx';

describe("BytesFormatter", ()=>{
    it("should format a number of bytes to bytes", ()=>{
        const rendered=shallow(<BytesFormatter value={100}/>);
        expect(rendered.find('span').text()).toEqual("100 bytes");
    });

    it("should format a number of kb to kb", ()=>{
        const rendered=shallow(<BytesFormatter value={2048}/>);
        expect(rendered.find('span').text()).toEqual("2 Kb");
    });

    it("should format a number of Mb to Mb", ()=>{
        const rendered=shallow(<BytesFormatter value={2097152}/>);
        expect(rendered.find('span').text()).toEqual("2 Mb");
    })

    it("should format a number of Gb to Gb", ()=>{
        const rendered=shallow(<BytesFormatter value={2147483648}/>);
        expect(rendered.find('span').text()).toEqual("2 Gb");
    })

    it("should format a number of Tb to Tb", ()=>{
        const rendered=shallow(<BytesFormatter value={2199023255552}/>);
        expect(rendered.find('span').text()).toEqual("2 Tb");
    })

    it("should format anything larger than Tb to Tb", ()=>{
        const rendered=shallow(<BytesFormatter value={2251799813685248}/>);
        expect(rendered.find('span').text()).toEqual("2048 Tb");
    })
});