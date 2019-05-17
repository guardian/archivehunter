import React from "react";
import axios from "axios";
import BreadcrumbComponent from "../common/BreadcrumbComponent.jsx";
import ErrorViewComponent from "../common/ErrorViewComponent.jsx";
import LoadingThrobber from "../common/LoadingThrobber.jsx";
import {Redirect} from "react-router-dom";
import {Controlled as CodeMirror} from 'react-codemirror2';
import 'codemirror/lib/codemirror.css';
import TestParametersForm from "./TestParametersForm.jsx";
//import 'codemirror/theme/material.css';

require('codemirror/mode/htmlembedded/htmlembedded');

class EmailTemplateEdit extends React.Component {
    constructor(props){
        super(props);

        this.state = {
            loading: false,
            saving: false,
            testing: false,
            testSuccess: false,
            lastError: null,
            templateName: null,     //must be alphanumeric, _ or -
            subjectPart: "",
            textPart: "",
            htmlPart: "",
            redirect_return: false,
            nameValidationError: null,
            testError: null,
            parametersList: [],
            testParametersValues: {},
            testEvent: ""
        };

        this.copyTextToHtml = this.copyTextToHtml.bind(this);
        this.submitCompletedForm = this.submitCompletedForm.bind(this);
        this.performTest = this.performTest.bind(this);
    }

    setIncomingTemplate() {
        return new Promise((resolve, reject)=>{
            if(this.props.match.params.templateName==="new"){
                resolve();
            } else {
                this.setState({templateName: this.props.match.params.templateName}, ()=>resolve());
            }
        })
    }

    loadTemplateToEdit() {
        this.setState({loading: true, lastError: null}, ()=>axios.get("/api/emailtemplate/" + this.state.templateName).then(response=>{
            this.setState({
                loading: false,
                lastError: null,
                subjectPart: response.data.entry.subjectPart,
                textPart: response.data.entry.textPart,
                htmlPart: response.data.entry.htmlPart
            }, ()=>this.extractParametersList())
        }).catch(err=>{
            console.error(err);
            this.setState({loading: false, lastError: err});
        }))
    }


    componentWillMount() {
        this.setIncomingTemplate().then( ignored=>{
            console.log(this.state);
            if(this.state.templateName) this.loadTemplateToEdit();
        })
    }

    copyTextToHtml(evt) {
        evt.preventDefault();
        this.setState({htmlPart: this.state.textPart})
    }

    static nameValidationExpression = /^[\w\d\-_]+$/;

    validateTemplateName(){
        if(EmailTemplateEdit.nameValidationExpression.test(this.state.templateName)){
            if(this.state.nameValidationError) this.setState({nameValidationError: null})
        } else {
            if(!this.state.nameValidationError) this.setState({nameValidationError: "Template name must have at least one character and only include alphanumeric characters, - and _"});
        }
    }

    submitCompletedForm(evt) {
        if (evt) evt.preventDefault();
        this.saveData().then(()=>this.setState({redirect_return: true}));
    }

    saveData(){
        return new Promise((resolve, reject)=> {
            const requestToSend = {
                //must mirror UpdateEmailTemplate
                name: this.state.templateName,
                subjectPart: this.state.subjectPart,
                textPart: this.state.textPart,
                htmlPart: this.state.htmlPart
            };

            this.setState({
                saving: true,
                lastError: null
            }, () => axios.post("/api/emailtemplate", requestToSend).then(response => {
                this.setState({saving: false, lastError: null}, ()=>resolve());
            }).catch(err => {
                console.error(err);
                this.setState({saving: false, lastError: err}, reject(err));
            }))
        });
    }

    performTest(evt) {
        if(evt) evt.preventDefault();
        const requestToSend = {
            //must mirror SendEmailTestRequest
            mockedAction: this.state.testEvent,
            templateParameters: this.state.testParametersValues
        };

        this.saveData().then(ignored=>{
            this.setState({testing: true, lastError: null}, ()=>
                axios.post("/api/emailtemplate/" + this.state.templateName + "/sendTest", requestToSend).then(response=>{
                    this.setState({testing: false, testSuccess: true, testError: null});
                }).catch(err=>{
                    console.error(err);
                    this.setState({testing: false, testSuccess:false, testError: err});
                })
            )
        })
    }

    //updates the parametersList state var by scanning the current text values and applying a regex
    static templateVarXtractor = /{{\s*([\w\d_\-]+)\s*}}/g;

    static extractParametersListInternal(str) {
        let newParamsList = [];
        let matches = [];
        let ctr=0;
        do {
            ctr++;
            matches = EmailTemplateEdit.templateVarXtractor.exec(str);
            if(matches===undefined) break;  //if we are getting broken output then don't lock into a loop
            if (matches) {
                newParamsList = newParamsList.concat(matches[1]);
            }
            if(ctr>1000) break; //safety -check so we don't lock into an infinite loop and crash the browser tab
        } while (matches);

        return newParamsList;
    }

    extractParametersList(){
        const textParamsList = EmailTemplateEdit.extractParametersListInternal(this.state.textPart);
        const htmlParamsList = EmailTemplateEdit.extractParametersListInternal(this.state.htmlPart);

        const newParamsList = [...new Set(htmlParamsList.concat(textParamsList))];

        this.setState({parametersList: newParamsList.sort()});
    }

    render(){
        if(this.state.redirect_return) return <Redirect to="/admin/emailtemplates"/>;

        return <div>
            <BreadcrumbComponent path={this.props.location.pathname}/>
            <form onSubmit={this.submitCompletedForm}>
                <table className="centered-table" style={{marginLeft: "inherit"}}>
                    <tbody>
                    <tr>
                        <td className="right">Template name</td>
                        <td>
                            <input className="full-width" value={this.state.templateName} onChange={evt=>this.setState({templateName: evt.target.value}, ()=>this.validateTemplateName())}/><br/>
                            <p className="error-text" style={{display: this.state.nameValidationError ? "block":"none"}}>{this.state.nameValidationError}</p>
                        </td>
                    </tr>
                    <tr>
                        <td className="right">Subject part</td>
                        <td><input className="full-width" value={this.state.subjectPart} onChange={evt=>this.setState({subjectPart: evt.target.value}, ()=>this.extractParametersList())}/> </td>
                    </tr>
                    <tr>
                        <td className="right">Email template text-only<br/><button onClick={this.copyTextToHtml}>Copy text to HTML</button></td>
                        <td><textarea className="large-textarea" value={this.state.textPart} onChange={evt=>this.setState({textPart: evt.target.value}, ()=>this.extractParametersList())}/></td>
                    </tr>
                    <tr>
                        <td className="right">Email template HTML</td>
                        <td>
                            {/*<textarea className="large-textarea" value={this.state.htmlPart} onChange={evt=>this.setState({htmlPart: evt.target.value})}/>*/}
                            <CodeMirror
                                value={this.state.htmlPart}
                                options={{
                                    mode: 'htmlembedded', lineNumbers: true
                                }}
                                onBeforeChange={(editor, data, value)=>{this.setState({htmlPart: value}, ()=>this.extractParametersList())}}
                                onChange={(editor, data, value)=> {}}
                            />
                        </td>
                    </tr>
                    </tbody>
                </table>
                <input type="submit" value="Save and return to list"/>
                <button type="button" onClick={this.performTest}>Save and test</button>
                <LoadingThrobber show={this.state.saving || this.state.testing} caption={this.state.testing ? "Testing..." : "Saving..."} small={true}/>
            </form>
            <p className="information">To cancel edits, reload the page (command-R, F5 or click the reload button in your browser).  To abort editing completely, click Back in your browser or hit Backspace</p>
            <ErrorViewComponent error={this.state.lastError}/>
            <ErrorViewComponent error={this.state.testError}/>

            <TestParametersForm paramList={this.state.parametersList}
                                onChange={newValues=>this.setState({testParametersValues: newValues})}
                                forEvent={this.state.testEvent}
                                onEventChanged={(newValue, templateName)=>this.setState({testEvent: newValue})}
                                className="centered-table"
            />
        </div>
    }
}

export default EmailTemplateEdit;