import React from "react";
import axios from "axios";
import BreadcrumbComponent from "../common/BreadcrumbComponent.jsx";
import ErrorViewComponent from "../common/ErrorViewComponent.jsx";
import LoadingThrobber from "../common/LoadingThrobber.jsx";
import {Redirect} from "react-router-dom";

class EmailTemplateEdit extends React.Component {
    constructor(props){
        super(props);

        this.state = {
            loading: false,
            saving: false,
            lastError: null,
            templateName: null,     //must be alphanumeric, _ or -
            subjectPart: "",
            textPart: "",
            htmlPart: "",
            redirect_return: false
        };

        this.copyTextToHtml = this.copyTextToHtml.bind(this);
        this.submitCompletedForm = this.submitCompletedForm.bind(this);
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
            })
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

    submitCompletedForm(evt) {
        evt.preventDefault();
        const requestToSend = {
            //must mirror UpdateEmailTemplate
            name: this.state.templateName,
            subjectPart: this.state.subjectPart,
            textPart: this.state.textPart,
            htmlPart: this.state.htmlPart
        };

        this.setState({saving: true, lastError:null }, ()=>axios.post("/api/emailtemplate", requestToSend).then(response=>{
            this.setState({saving: false, lastError: null,  redirect_return: true});
        }).catch(err=>{
            console.error(err);
            this.setState({saving: false, lastError: err});
        }))
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
                        <td><input className="full-width" value={this.state.templateName} onChange={evt=>this.setState({templateName: evt.target.value})}/> </td>
                    </tr>
                    <tr>
                        <td className="right">Subject part</td>
                        <td><input className="full-width" value={this.state.subjectPart} onChange={evt=>this.setState({subjectPart: evt.target.value})}/> </td>
                    </tr>
                    <tr>
                        <td className="right">Email template text-only<br/><button onClick={this.copyTextToHtml}>Copy text to HTML</button></td>
                        <td><textarea className="large-textarea" value={this.state.textPart} onChange={evt=>this.setState({textPart: evt.target.value})}/></td>
                    </tr>
                    <tr>
                        <td className="right">Email template HTML</td>
                        <td><textarea className="large-textarea" value={this.state.htmlPart} onChange={evt=>this.setState({htmlPart: evt.target.value})}/></td>
                    </tr>
                    </tbody>
                </table>
                <input type="submit"/><LoadingThrobber show={this.state.saving} caption="Saving..." small={true}/>
            </form>
            <p className="information">To cancel edits, reload the page.  To abort editing completely, click Back in your browser or hit Backspace</p>
            <ErrorViewComponent error={this.state.lastError}/>
        </div>
    }
}

export default EmailTemplateEdit;