import React from 'react';
import {shallow,mount} from 'enzyme';
import TimestampFormatter from '../../app/common/TimestampFormatter.jsx';
import sinon from 'sinon';
import assert from 'assert';

describe("TimestampFormatter", ()=> {
    it("should display the time provided to it", () => {
        const rendered = shallow(<TimestampFormatter relative={false} value="2018-01-01T12:13:14+00:00"/>);
        expect(rendered.find('span.timestamp').text()).toEqual("2018-01-01T12:13:14+00:00");
    });
});