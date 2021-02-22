import React from 'react';
import {shallow,mount} from 'enzyme';
import TimestampFormatter from '../../app/common/TimestampFormatter';

describe("TimestampFormatter", ()=> {
    it("should display the time provided to it", () => {
        const rendered = mount(<TimestampFormatter relative={false} value="2018-01-01T12:13:14+00:00"/>);
        expect(rendered.text()).toEqual("2018-01-01T12:13:14+00:00");
    });
});