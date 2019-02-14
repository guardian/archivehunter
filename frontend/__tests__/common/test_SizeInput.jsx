import React from 'react';
import {shallow,mount} from 'enzyme';
import SizeInput from '../../app/common/SizeInput.jsx';
import sinon from 'sinon';

describe("SizeInput", ()=>{
   it("should set its internal values based on incoming props", ()=>{
       const updateCbMock = sinon.spy();
       const rendered = mount(<SizeInput sizeInBytes={20480} didUpdate={updateCbMock}/>);

       expect(rendered.find("input").props().value).toEqual(20);
       expect(rendered.instance().state.multiplier).toEqual(1);
   }) 
});