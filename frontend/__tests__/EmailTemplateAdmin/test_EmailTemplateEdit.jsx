import React from 'react';
import {shallow,mount} from 'enzyme';
import EmailTemplateEdit from '../../app/EmailTemplateAdmin/EmailTemplateEdit.jsx';
import sinon from 'sinon';
import assert from 'assert';

describe("EmailTemplateEdit.extractParametersListInternal", ()=>{
    it("should return a list of all {{}} delimited parameters in the text string", ()=>{
        const textString = "This is a string with {{parameters}} {{ like }} {{this   }}";
        const result = EmailTemplateEdit.extractParametersListInternal(textString);
        expect(result).toEqual(["parameters","like","this"]);
    });

    it("should not fail on an empty string", ()=>{
        const textString = "";
        const result = EmailTemplateEdit.extractParametersListInternal(textString);
        expect(result).toEqual([]);
    });

    it("should not fail on null", ()=>{
        const textString = null;
        const result = EmailTemplateEdit.extractParametersListInternal(textString);
        expect(result).toEqual([]);
    });
});

describe("EmailTemplateExit.extractParametersList", ()=>{
    it("should extract, de-duplicate and sort parameters from both html and text parts", ()=>{
        const rendered = shallow(<EmailTemplateEdit location={{pathname: "testing"}}/>);

        rendered.instance().setState({
            textPart: "Some plaintext with {{substitution}} {{or}} {{two}}",
            htmlPart: '\<p\>Some html with {{another}} {{substitution}}\</p\>',
            subjectPart: "",
            parametersList: []
        });

        rendered.instance().extractParametersList();

        expect(rendered.instance().state.parametersList).toEqual(["another", "or", "substitution", "two"]);
    })
});