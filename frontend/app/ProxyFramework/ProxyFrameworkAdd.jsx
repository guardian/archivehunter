import React from 'react';
import MultiStep from 'react-multistep';
import FindDeploymentComponent from './multistep/FindDeploymentComponent.jsx';
import InitiateAddComponent from "./multistep/InitiateAddComponent.jsx";
import ConfirmationComponent from "./multistep/ConfirmationComponent.jsx";
import ErrorViewComponent from "../common/ErrorViewComponent.jsx";
import BreadcrumbComponent from "../common/BreadcrumbComponent.jsx";

class ProxyFrameworkAdd extends React.Component {
    constructor(props){
        super(props);
        this.state = {
            findBySearch: true,
            selectedDeployment: null,
            manualInput: null
        }
    }

    render(){
        if(this.state.lastError) return <ErrorViewComponent error={this.state.lastError}/>;
        return <div>
            <BreadcrumbComponent path={this.props.location.pathname}/>
            <MultiStep showNavigation={true} steps={
                [
                    {name: "Add deployment", component:<InitiateAddComponent
                            willSearchUpdated={newValue=>this.setState({findBySearch: newValue})}/>},

                    {name: "Find deployment", component:<FindDeploymentComponent
                            shouldSearch={this.state.findBySearch}
                            deploymentSelected={newValue=>this.setState({selectedDeployment: newValue})}
                            manualInputSelected={newValue=>this.setState({manualInput: newValue})}
                            currentSelectedDeployment={this.state.selectedDeployment}
                        />},

                    {name: "Confirm", component: <ConfirmationComponent foundBySearch={this.state.findBySearch}
                                                                        selectedDeployment={this.state.selectedDeployment}
                                                                        manualInput={this.state.manualInput}
                        />}
                ]
            }/>
        </div>
    }
}

export default ProxyFrameworkAdd;